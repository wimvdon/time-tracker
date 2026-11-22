package to.wim.timetracker

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
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
     * The app icon's stopwatch (see `ic_launcher_foreground.xml`, a 108×108 grid) in the
     * workspace colour instead of the orange gradient, on a plate tinted with the same colour,
     * as a full-bleed adaptive-icon bitmap. The elapsed-time slice fades in from 12 o'clock to
     * the hand, like the app icon's.
     */
    private fun clockIcon(context: Context, fill: Int): Bitmap {
        val size = (108 * context.resources.displayMetrics.density).toInt().coerceIn(108, 432)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val unit = size / 108f
        canvas.scale(unit, unit)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        // Plate.
        val (plateTop, plateBottom) = ShortcutIconColor.plateArgb(fill)
        paint.shader = LinearGradient(18f, 18f, 90f, 90f, plateTop, plateBottom, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, 108f, 108f, paint)
        paint.shader = null
        // Body, crown on its stem, and the side button at half past one.
        paint.color = fill
        canvas.drawCircle(54f, 59f, 22f, paint)
        canvas.drawRoundRect(RectF(48f, 25f, 60f, 32f), 3f, 3f, paint)
        canvas.drawRect(51.5f, 30f, 56.5f, 39f, paint)
        canvas.save()
        canvas.rotate(45f, 54f, 59f)
        canvas.drawRect(50.5f, 32f, 57.5f, 38.5f, paint)
        canvas.restore()
        // Elapsed time: a slice from 12 o'clock to 130°, transparent at the start, solid at the end.
        val slice = ShortcutIconColor.sliceArgb(fill)
        val clear = slice and 0x00FFFFFF
        val sweep = SweepGradient(54f, 59f, intArrayOf(clear, slice), floatArrayOf(0f, 130f / 360f))
        // Android's sweep starts at 3 o'clock; turn it to start at 12.
        sweep.setLocalMatrix(Matrix().apply { setRotate(-90f, 54f, 59f) })
        paint.shader = sweep
        canvas.drawArc(RectF(38f, 43f, 70f, 75f), -90f, 130f, true, paint)
        paint.shader = null
        return bitmap
    }
}
