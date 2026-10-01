/*
 * Copyright 2026, Lawnchair
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package app.lawnchair.dream

import android.annotation.SuppressLint
import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.ConnectivityManager
import android.net.Network
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.dreams.DreamService
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import app.lawnchair.weather.WeatherCodes
import app.lawnchair.weather.WeatherStore
import app.lawnchair.weather.WeatherWidgetProvider
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONArray
import org.json.JSONObject

/**
 * A screen saver that shows an HTML page of the user's choosing — a web address, a
 * file picked from the device, or the bundled sample — in a full-screen WebView.
 *
 * A web address can be unreachable, typically when the phone is charging away from
 * the network the page lives on. The bundled clock is shown in that case instead of
 * an error page, and the address keeps being retried so the real page takes over as
 * soon as it can be reached again.
 *
 * The page is told about the room, the battery and the weather through a
 * `lawnchair` window event and `window.lawnchairState`; see [pushState]. Data only flows into the page — it
 * gets no handle to call back into the app, so pointing the screen saver at a remote
 * site does not hand that site any control of the phone.
 */
class HtmlDreamService : DreamService() {

    private val handler = Handler(Looper.getMainLooper())
    private var webView: WebView? = null
    private var target = HtmlDreamStore.DEFAULT_URL
    private var showingFallback = false
    private var probing = false

    private var lux: Float? = null
    private var dark: Boolean? = null
    private var batteryLevel: Int? = null
    private var charging: Boolean? = null
    private var plugged: String? = null
    private var lastPush = 0L
    private var pushPending = false

    private val retry = object : Runnable {
        override fun run() {
            probe()
            handler.postDelayed(this, RETRY_INTERVAL_MS)
        }
    }

    /** A pending check that the page actually finished, rather than hung. */
    private val loadTimeout = Runnable { fallBack() }

    private val pushRunnable = Runnable {
        pushPending = false
        pushState()
    }

    /**
     * Weather reuses what the clock widget already fetched, so it works offline with
     * the last known reading; while the screen saver runs it also keeps that cache
     * fresh and re-reads it.
     */
    private val weatherTick = object : Runnable {
        override fun run() {
            weatherWidgetId()?.let { WeatherWidgetProvider.refresh(this@HtmlDreamService, it) }
            // The refresh lands asynchronously; pick the result up a little later.
            handler.postDelayed({ schedulePush(urgent = true) }, WEATHER_SETTLE_MS)
            handler.postDelayed(this, WEATHER_INTERVAL_MS)
        }
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            // Reconnecting is the likeliest moment for the page to become reachable.
            handler.post { if (showingFallback) probe() }
        }
    }

    private val lightListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val value = event.values.firstOrNull() ?: return
            lux = value
            // Two thresholds rather than one, so a room sitting right at the edge does
            // not flick between day and night colours.
            val wasDark = dark
            dark = when {
                value <= DARK_BELOW_LUX -> true
                value >= LIGHT_ABOVE_LUX -> false
                else -> wasDark ?: false
            }
            schedulePush(urgent = dark != wasDark)
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            readBattery(intent)
            schedulePush(urgent = true)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = HtmlDreamStore.interactive(this)
        isFullscreen = true
        isScreenBright = true
        // Follow how the phone is actually standing, regardless of the rotation lock,
        // so laying it sideways on a stand gives the landscape layout as StandBy does.
        window?.let {
            it.attributes = it.attributes.apply {
                screenOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR
            }
        }

        target = HtmlDreamStore.url(this)
        val view = WebView(this).apply {
            setBackgroundColor(Color.BLACK)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            // A page picked through the system file picker arrives as a content:// uri.
            settings.allowContentAccess = true
            settings.mediaPlaybackRequiresUserGesture = false
            webViewClient = Client()
        }
        webView = view
        setContentView(view)

        startSensing()
        loadTarget()
        handler.post(weatherTick)

        if (isRemote(target)) {
            runCatching {
                getSystemService(ConnectivityManager::class.java)
                    ?.registerDefaultNetworkCallback(networkCallback)
            }
        }
    }

    override fun onDreamingStarted() {
        super.onDreamingStarted()
        webView?.onResume()
    }

    override fun onDreamingStopped() {
        webView?.onPause()
        super.onDreamingStopped()
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacksAndMessages(null)
        stopSensing()
        runCatching {
            getSystemService(ConnectivityManager::class.java)
                ?.unregisterNetworkCallback(networkCallback)
        }
        webView?.destroy()
        webView = null
        super.onDetachedFromWindow()
    }

    private fun startSensing() {
        // Battery is a sticky broadcast, so registering also returns the current state.
        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?.let(::readBattery)
        val sensors = getSystemService(SensorManager::class.java)
        sensors?.getDefaultSensor(Sensor.TYPE_LIGHT)?.let {
            sensors.registerListener(lightListener, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
    }

    private fun stopSensing() {
        runCatching { unregisterReceiver(batteryReceiver) }
        getSystemService(SensorManager::class.java)?.unregisterListener(lightListener)
    }

    private fun readBattery(intent: Intent) {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level >= 0 && scale > 0) batteryLevel = level * 100 / scale
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val plug = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
        charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL || plug != 0
        plugged = when (plug) {
            BatteryManager.BATTERY_PLUGGED_AC -> "ac"
            BatteryManager.BATTERY_PLUGGED_USB -> "usb"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> "wireless"
            BatteryManager.BATTERY_PLUGGED_DOCK -> "dock"
            else -> null
        }
    }

    /**
     * The light sensor reports several times a second; the page only needs to hear
     * about it every so often, except when day turns to night or a cable is
     * connected, which it should see straight away.
     */
    private fun schedulePush(urgent: Boolean) {
        val since = SystemClock.uptimeMillis() - lastPush
        if (urgent || since >= PUSH_INTERVAL_MS) {
            handler.removeCallbacks(pushRunnable)
            pushPending = false
            pushState()
        } else if (!pushPending) {
            pushPending = true
            handler.postDelayed(pushRunnable, PUSH_INTERVAL_MS - since)
        }
    }

    /**
     * Hands the page a snapshot as `window.lawnchairState` and a `lawnchair` event:
     * `{ lux, dark, battery, charging, plugged, weather }`. Fields the device cannot provide
     * are null, so a page should keep its own fallback (the bundled one uses the
     * time of day when there is no light reading).
     */
    private fun pushState() {
        val view = webView ?: return
        lastPush = SystemClock.uptimeMillis()
        val state = JSONObject().apply {
            put("lux", lux?.toDouble() ?: JSONObject.NULL)
            put("dark", dark ?: JSONObject.NULL)
            put("battery", batteryLevel ?: JSONObject.NULL)
            put("charging", charging ?: JSONObject.NULL)
            put("plugged", plugged ?: JSONObject.NULL)
            put("weather", weatherJson() ?: JSONObject.NULL)
        }
        view.evaluateJavascript(
            "window.lawnchairState=$state;" +
                "window.dispatchEvent(new CustomEvent('lawnchair',{detail:window.lawnchairState}));",
            null,
        )
    }

    /** The first clock-and-weather widget that has a place chosen, if any. */
    private fun weatherWidgetId(): Int? = runCatching {
        AppWidgetManager.getInstance(this)
            .getAppWidgetIds(ComponentName(this, WeatherWidgetProvider::class.java))
            .firstOrNull { WeatherStore.loadPlace(this, it) != null }
    }.getOrNull()

    /**
     * `{ place, temp, feels, high, low, code, condition, fetchedAt, week: [{ date,
     * code, high, low }] }`, where week spans a week either side of today.
     */
    private fun weatherJson(): JSONObject? {
        val id = weatherWidgetId() ?: return null
        val place = WeatherStore.loadPlace(this, id) ?: return null
        val reading = WeatherStore.loadReading(this, id) ?: return null
        val week = JSONArray()
        reading.week.forEach {
            week.put(
                JSONObject()
                    .put("date", it.date)
                    .put("code", it.code)
                    .put("high", it.high)
                    .put("low", it.low),
            )
        }
        return JSONObject()
            .put("place", place.name.substringBefore(','))
            .put("temp", reading.temperature)
            .put("feels", reading.feelsLike)
            .put("high", reading.high)
            .put("low", reading.low)
            .put("code", reading.code)
            .put("condition", getString(WeatherCodes.label(reading.code)))
            .put("fetchedAt", reading.fetchedAt)
            .put("week", week)
    }

    private fun loadTarget() {
        showingFallback = false
        handler.removeCallbacks(retry)
        if (isRemote(target)) handler.postDelayed(loadTimeout, LOAD_TIMEOUT_MS)
        webView?.loadUrl(target)
    }

    /** Switches to the bundled clock and starts watching for the page to come back. */
    private fun fallBack() {
        handler.removeCallbacks(loadTimeout)
        if (showingFallback || !isRemote(target)) return
        showingFallback = true
        webView?.loadUrl(HtmlDreamStore.DEFAULT_URL + "#offline")
        handler.removeCallbacks(retry)
        handler.postDelayed(retry, RETRY_INTERVAL_MS)
    }

    /**
     * Checks reachability off the main thread with a plain request, so a still-dead
     * address does not flash an error page over the fallback every retry.
     */
    private fun probe() {
        if (probing || !showingFallback) return
        probing = true
        val url = target
        Thread {
            val ok = try {
                val connection = URL(url).openConnection() as HttpURLConnection
                connection.connectTimeout = PROBE_TIMEOUT_MS
                connection.readTimeout = PROBE_TIMEOUT_MS
                connection.instanceFollowRedirects = true
                try {
                    connection.responseCode in 200..399
                } finally {
                    connection.disconnect()
                }
            } catch (e: Exception) {
                false
            }
            handler.post {
                probing = false
                if (ok && showingFallback && webView != null) loadTarget()
            }
        }.start()
    }

    private inner class Client : WebViewClient() {

        override fun onPageFinished(view: WebView, url: String) {
            super.onPageFinished(view, url)
            if (!showingFallback) handler.removeCallbacks(loadTimeout)
            // A freshly loaded page has missed everything pushed before it existed.
            pushState()
        }

        override fun onReceivedError(
            view: WebView,
            request: WebResourceRequest,
            error: WebResourceError,
        ) {
            // Only the page itself matters; a broken image or script inside a page
            // that did load is not a reason to throw it away.
            if (request.isForMainFrame) fallBack()
        }

        override fun onReceivedHttpError(
            view: WebView,
            request: WebResourceRequest,
            errorResponse: WebResourceResponse,
        ) {
            if (request.isForMainFrame && errorResponse.statusCode >= 400) fallBack()
        }
    }

    private companion object {
        const val LOAD_TIMEOUT_MS = 15_000L
        const val RETRY_INTERVAL_MS = 30_000L
        const val PROBE_TIMEOUT_MS = 5_000
        const val PUSH_INTERVAL_MS = 2_000L
        const val WEATHER_INTERVAL_MS = 10 * 60_000L
        const val WEATHER_SETTLE_MS = 20_000L

        /** Roughly a room with the lights off. */
        const val DARK_BELOW_LUX = 5f
        const val LIGHT_ABOVE_LUX = 15f

        fun isRemote(url: String) = url.startsWith("http://") || url.startsWith("https://")
    }
}
