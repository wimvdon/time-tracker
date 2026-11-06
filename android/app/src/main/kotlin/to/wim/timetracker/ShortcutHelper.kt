package to.wim.timetracker

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import to.wim.timetracker.data.models.ShortcutIconColor
import to.wim.timetracker.data.models.Workspace

object ShortcutHelper {
    const val EXTRA_WORKSPACE_ID = "preset_workspace_id"
    const val EXTRA_PRESET_ID = "preset_id"

    fun updateShortcuts(context: Context, workspaces: List<Workspace>) {
        try {
            val max = ShortcutManagerCompat.getMaxShortcutCountPerActivity(context).coerceAtMost(4)
            val shortcuts = workspaces
                .flatMap { ws -> ws.presets.map { ws to it } }
                .take(max)
                .map { (ws, preset) ->
                    val intent = Intent(context, MainActivity::class.java).apply {
                        action = Intent.ACTION_VIEW
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                        putExtra(EXTRA_WORKSPACE_ID, ws.id)
                        putExtra(EXTRA_PRESET_ID, preset.id)
                    }
                    val label = preset.name
                    val builder = ShortcutInfoCompat.Builder(context, "preset_${ws.id}_${preset.id}")
                        .setShortLabel(label.take(25))
                        .setLongLabel(label.take(50))
                        .setIntent(intent)

                    runCatching {
                        val fill = ShortcutIconColor.fillArgb(ws.color)
                        builder.setIcon(
                            if (fill != null) IconCompat.createWithAdaptiveBitmap(clockIcon(context, fill))
                            else IconCompat.createWithResource(context, R.mipmap.ic_launcher)
                        )
                    }
                    builder.build()
                }
            ShortcutManagerCompat.setDynamicShortcuts(context, shortcuts)
        } catch (e: Exception) {
            android.util.Log.e("ShortcutHelper", "Failed to update dynamic launcher shortcuts", e)
        }
    }

    /**
     * The app icon's clock (see `ic_launcher_foreground.xml`, a 108×108 grid)
     * with its dial in the workspace colour instead of the orange gradient, on
     * the same white background, as a full-bleed adaptive-icon bitmap.
     */
    private fun clockIcon(context: Context, fill: Int): Bitmap {
        val size = (108 * context.resources.displayMetrics.density).toInt().coerceIn(108, 432)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val unit = size / 108f
        canvas.scale(unit, unit)
        canvas.drawColor(android.graphics.Color.WHITE)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        // Dial.
        paint.color = fill
        canvas.drawCircle(54f, 54f, 27f, paint)
        // Elapsed-time wedge, from 12 o'clock to half past four.
        paint.color = HAND_COLOR
        paint.alpha = (0.35f * 255).toInt()
        canvas.drawArc(RectF(34f, 34f, 74f, 74f), -90f, 135f, true, paint)
        // Hand.
        paint.alpha = 255
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 5.5f
        paint.strokeCap = Paint.Cap.ROUND
        canvas.drawLine(54f, 54f, 65.67f, 65.67f, paint)
        // Centre dot.
        paint.style = Paint.Style.FILL
        canvas.drawCircle(54f, 54f, 4.2f, paint)
        return bitmap
    }

    private const val HAND_COLOR = 0xFFFAFAFA.toInt()
}
