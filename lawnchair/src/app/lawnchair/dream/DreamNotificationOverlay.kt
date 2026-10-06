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

import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.android.launcher3.R
import com.android.launcher3.notification.NotificationKeyData
import com.android.launcher3.notification.NotificationListener
import com.android.launcher3.util.PackageUserKey

/**
 * Notifications drawn natively on top of the screen saver's page. The system does not
 * show heads-up notifications over a dream, so without this they would only be seen
 * after waking the phone.
 *
 * Nothing here is handed to the page: it is drawn by the app above the WebView, so a
 * remote page gets no more access to notifications than any other website. What is
 * shown follows the lock screen's rules, since a phone on a stand is usually locked:
 * notifications hidden from the lock screen stay hidden, and sensitive ones show only
 * the app name.
 *
 * Notifications come from the launcher's own listener (the one behind notification
 * dots), so this needs no access beyond what dots already have; with dots turned off
 * nothing is shown.
 */
class DreamNotificationOverlay(context: Context) : FrameLayout(context) {

    private val active = LinkedHashMap<String, StatusBarNotification>()
    private val icons = HashMap<String, Drawable?>()
    private var night = false

    private val iconRow = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(10), dp(6), dp(10), dp(6))
        visibility = GONE
    }

    private val bannerIcon = ImageView(context)
    private val bannerApp = text(13f)
    private val bannerTitle = text(16f).apply { typeface = Typeface.DEFAULT_BOLD }
    private val bannerText = text(15f).apply { maxLines = 2 }

    private val banner = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(16), dp(12), dp(18), dp(12))
        alpha = 0f
        visibility = GONE
        addView(bannerIcon, LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(14) })
        addView(
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(bannerApp)
                addView(bannerTitle)
                addView(bannerText)
            },
            LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f),
        )
    }

    private val hideBanner = Runnable {
        banner.animate().alpha(0f).translationY(-dp(12).toFloat()).setDuration(ANIM_MS)
            .withEndAction { banner.visibility = GONE }
    }

    private val listener = object : NotificationListener.NotificationsChangedListener {
        override fun onNotificationPosted(key: PackageUserKey, data: NotificationKeyData) {
            val sbn = fetch(data.notificationKey) ?: return
            if (!relevant(sbn)) {
                active.remove(sbn.key)?.let { renderRow() }
                return
            }
            val previous = active.put(sbn.key, sbn)
            renderRow()
            // Updates to a notification already on screen (progress, edits) stay quiet
            // unless the app asks to alert again, as the status bar treats them.
            val alertsAgain = previous != null && sbn.postTime > previous.postTime &&
                sbn.notification.flags and Notification.FLAG_ONLY_ALERT_ONCE == 0
            if (previous == null || alertsAgain) showBanner(sbn)
        }

        override fun onNotificationRemoved(key: PackageUserKey, data: NotificationKeyData) {
            if (active.remove(data.notificationKey) != null) renderRow()
        }

        override fun onNotificationFullRefresh(activeNotifications: List<StatusBarNotification>) {
            active.clear()
            activeNotifications.filter(::relevant).forEach { active[it.key] = it }
            renderRow()
        }
    }

    init {
        addView(
            iconRow,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(ROW_GAP) },
        )
        val width = minOf(resources.displayMetrics.widthPixels * 9 / 10, dp(560))
        addView(
            banner,
            LayoutParams(width, LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(ROW_GAP + BANNER_BELOW_ROW) },
        )
        applyColours()
    }

    /** Keeps the row below the camera cutout, which the full-screen dream draws under. */
    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        val top = cameraBottom(insets)
        (iconRow.layoutParams as LayoutParams).topMargin = top + dp(ROW_GAP)
        (banner.layoutParams as LayoutParams).topMargin = top + dp(ROW_GAP + BANNER_BELOW_ROW)
        requestLayout()
        return super.onApplyWindowInsets(insets)
    }

    /**
     * Where the camera hole ends. Its actual outline is used rather than the safe inset
     * or bounding rect, which are padded to the whole status bar height and would push
     * the row onto the page's clock.
     */
    private fun cameraBottom(insets: WindowInsets): Int {
        val cutout = insets.displayCutout ?: return 0
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            cutout.cutoutPath?.let { path ->
                val bounds = RectF()
                path.computeBounds(bounds, true)
                // Only a cutout at the top edge is in the way; one on a side is not.
                if (!bounds.isEmpty && bounds.top < resources.displayMetrics.heightPixels / 4f) return bounds.bottom.toInt()
                if (!bounds.isEmpty) return 0
            }
        }
        return cutout.boundingRectTop.takeUnless { it.isEmpty }?.bottom ?: 0
    }

    fun start() = NotificationListener.addNotificationsChangedListener(listener)

    fun stop() {
        NotificationListener.removeNotificationsChangedListener(listener)
        removeCallbacks(hideBanner)
    }

    /** Matches the page's night mode: dim red on black, so it never lights up the room. */
    fun setNight(value: Boolean) {
        if (night == value) return
        night = value
        applyColours()
        renderRow()
        bannerIcon.colorFilter = iconFilter()
    }

    private fun fetch(key: String): StatusBarNotification? = runCatching {
        NotificationListener.getInstanceIfConnected()?.getActiveNotifications(arrayOf(key))
            ?.firstOrNull()
    }.getOrNull()

    private fun ranking(sbn: StatusBarNotification): NotificationListenerService.Ranking? =
        runCatching {
            val ranking = NotificationListenerService.Ranking()
            val found = NotificationListener.getInstanceIfConnected()?.currentRanking
                ?.getRanking(sbn.key, ranking) == true
            if (found) ranking else null
        }.getOrNull()

    /** Ongoing ones (music, navigation, downloads) are not news, and some never show locked. */
    private fun relevant(sbn: StatusBarNotification): Boolean {
        val flags = sbn.notification.flags
        if (flags and (Notification.FLAG_ONGOING_EVENT or Notification.FLAG_FOREGROUND_SERVICE) != 0) {
            return false
        }
        return privacy(sbn) != Privacy.HIDDEN
    }

    private enum class Privacy { FULL, APP_ONLY, HIDDEN }

    /** The lock screen's rules, applied only while the phone is actually locked. */
    private fun privacy(sbn: StatusBarNotification): Privacy {
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        val locked = keyguard?.isKeyguardLocked != false && keyguard?.isDeviceSecure != false
        if (!locked) return Privacy.FULL
        if (secureSetting(LOCK_SCREEN_SHOW, 1) == 0) return Privacy.HIDDEN
        val override = ranking(sbn)?.lockscreenVisibilityOverride
            ?: NotificationListenerService.Ranking.VISIBILITY_NO_OVERRIDE
        val visibility = if (override != NotificationListenerService.Ranking.VISIBILITY_NO_OVERRIDE) {
            override
        } else {
            sbn.notification.visibility
        }
        return when {
            visibility == Notification.VISIBILITY_SECRET -> Privacy.HIDDEN
            visibility == Notification.VISIBILITY_PRIVATE -> Privacy.APP_ONLY
            // When the setting cannot be read, assume content is hidden.
            secureSetting(LOCK_SCREEN_ALLOW_PRIVATE, 0) == 0 -> Privacy.APP_ONLY
            else -> Privacy.FULL
        }
    }

    private fun secureSetting(name: String, fallback: Int) =
        runCatching { Settings.Secure.getInt(context.contentResolver, name, fallback) }
            .getOrDefault(fallback)

    private fun showBanner(sbn: StatusBarNotification) {
        val ranking = ranking(sbn)
        // Quiet notifications and ones held back by Do Not Disturb do not pop up on
        // the phone either; they still appear in the row of icons.
        if (ranking != null) {
            if (!ranking.matchesInterruptionFilter()) return
            if (ranking.importance < NotificationManager.IMPORTANCE_DEFAULT) return
        }
        val privacy = privacy(sbn)
        if (privacy == Privacy.HIDDEN) return

        val app = appLabel(sbn.packageName)
        var title: CharSequence? = null
        var body: CharSequence? = null
        if (privacy == Privacy.FULL) {
            val extras = sbn.notification.extras
            title = extras.getCharSequence(Notification.EXTRA_TITLE)
            body = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
                ?: extras.getCharSequence(Notification.EXTRA_TEXT)
        } else {
            // The redacted version the app supplies for lock screens, if any.
            sbn.notification.publicVersion?.extras?.let {
                title = it.getCharSequence(Notification.EXTRA_TITLE)
                body = it.getCharSequence(Notification.EXTRA_TEXT)
            }
            if (title.isNullOrEmpty() && body.isNullOrEmpty()) {
                title = context.getString(R.string.html_dream_notification_hidden)
            }
        }
        bannerApp.text = app
        bannerTitle.text = title
        bannerTitle.visibility = if (title.isNullOrEmpty()) GONE else VISIBLE
        bannerText.text = body
        bannerText.visibility = if (body.isNullOrEmpty()) GONE else VISIBLE
        bannerIcon.setImageDrawable(appIcon(sbn.packageName))
        bannerIcon.colorFilter = iconFilter()

        removeCallbacks(hideBanner)
        if (banner.visibility != VISIBLE) {
            banner.visibility = VISIBLE
            banner.translationY = -dp(12).toFloat()
        }
        banner.animate().alpha(1f).translationY(0f).setDuration(ANIM_MS).withEndAction(null)
        postDelayed(hideBanner, BANNER_MS)
    }

    /** One icon per app, newest first. */
    private fun renderRow() {
        iconRow.removeAllViews()
        val packages = active.values.sortedByDescending { it.postTime }
            .map { it.packageName }.distinct()
        if (packages.isEmpty()) {
            iconRow.visibility = GONE
            return
        }
        packages.take(MAX_ICONS).forEachIndexed { i, pkg ->
            iconRow.addView(
                ImageView(context).apply {
                    setImageDrawable(appIcon(pkg))
                    colorFilter = iconFilter()
                },
                LinearLayout.LayoutParams(dp(24), dp(24)).apply { if (i > 0) marginStart = dp(8) },
            )
        }
        if (packages.size > MAX_ICONS) {
            iconRow.addView(
                text(13f).apply { text = "+${packages.size - MAX_ICONS}" },
                LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
                    .apply { marginStart = dp(8) },
            )
        }
        applyColours()
        iconRow.visibility = VISIBLE
    }

    /**
     * A copy per view: the night tint is applied to the drawable itself, and on a
     * shared instance it would stay red after a new view without a tint reused it.
     */
    private fun appIcon(pkg: String): Drawable? = icons.getOrPut(pkg) {
        runCatching { context.packageManager.getApplicationIcon(pkg) }.getOrNull()
    }?.let { it.constantState?.newDrawable(resources)?.mutate() ?: it }

    private fun appLabel(pkg: String): CharSequence = runCatching {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0))
    }.getOrDefault(pkg)

    private fun applyColours() {
        val fg = if (night) NIGHT_FG else Color.WHITE
        val dim = if (night) NIGHT_DIM else DAY_DIM
        iconRow.background = pill(if (night) NIGHT_CARD else ROW_CARD)
        banner.background = pill(if (night) NIGHT_CARD else BANNER_CARD, dp(22).toFloat())
        bannerApp.setTextColor(dim)
        bannerTitle.setTextColor(fg)
        bannerText.setTextColor(fg)
        for (i in 0 until iconRow.childCount) {
            (iconRow.getChildAt(i) as? TextView)?.setTextColor(dim)
        }
    }

    /** Colour app icons would glare at night, so they are turned into dim red. */
    private fun iconFilter(): ColorMatrixColorFilter? {
        if (!night) return null
        val grey = ColorMatrix().apply { setSaturation(0f) }
        val red = ColorMatrix(
            floatArrayOf(
                0.75f, 0f, 0f, 0f, 0f,
                0f, 0.1f, 0f, 0f, 0f,
                0f, 0f, 0.08f, 0f, 0f,
                0f, 0f, 0f, 1f, 0f,
            ),
        )
        grey.postConcat(red)
        return ColorMatrixColorFilter(grey)
    }

    private fun pill(color: Int, radius: Float = dp(999).toFloat()) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius
    }

    private fun text(sp: Float) = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        setShadowLayer(dp(4).toFloat(), 0f, dp(1).toFloat(), SHADOW)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val BANNER_MS = 6_000L
        const val ANIM_MS = 250L
        const val MAX_ICONS = 8
        const val ROW_GAP = 10
        const val BANNER_BELOW_ROW = 46

        const val LOCK_SCREEN_SHOW = "lock_screen_show_notifications"
        const val LOCK_SCREEN_ALLOW_PRIVATE = "lock_screen_allow_private_notifications"

        val ROW_CARD = Color.argb(70, 20, 20, 26)
        val BANNER_CARD = Color.argb(225, 20, 20, 26)
        val NIGHT_CARD = Color.argb(230, 20, 0, 0)
        val DAY_DIM = Color.argb(190, 255, 255, 255)
        val NIGHT_FG = Color.rgb(0xff, 0x3b, 0x30)
        val NIGHT_DIM = Color.rgb(0x7a, 0x1a, 0x14)
        val SHADOW = Color.argb(160, 0, 0, 0)
    }
}
