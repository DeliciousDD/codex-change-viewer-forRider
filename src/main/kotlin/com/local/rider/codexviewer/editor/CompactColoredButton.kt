package com.local.rider.codexviewer.editor

import com.intellij.util.ui.JBUI
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.JButton

internal class CompactColoredButton(
    text: String,
    private val fillColor: Color,
    width: Int,
    height: Int = 22,
) : JButton(text) {
    init {
        isFocusable = false
        isFocusPainted = false
        isBorderPainted = false
        isContentAreaFilled = false
        isOpaque = false
        margin = JBUI.emptyInsets()
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        foreground = Color.WHITE
        preferredSize = JBUI.size(width, height)
        minimumSize = Dimension(preferredSize)
        maximumSize = Dimension(preferredSize)
        font = font.deriveFont((font.size2D - 1f).coerceAtLeast(10f))
    }

    override fun paintComponent(graphics: Graphics) {
        val copy = graphics.create() as Graphics2D
        try {
            copy.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            copy.color = when {
                !isEnabled -> Color(fillColor.red, fillColor.green, fillColor.blue, 105)
                model.isPressed -> fillColor.darker()
                else -> fillColor
            }
            val arc = JBUI.scale(7)
            copy.fillRoundRect(0, 0, width, height, arc, arc)
            copy.color = foreground
            copy.font = font
            val metrics = copy.fontMetrics
            copy.drawString(text, (width - metrics.stringWidth(text)).coerceAtLeast(0) / 2, (height - metrics.height) / 2 + metrics.ascent)
        } finally {
            copy.dispose()
        }
    }
}
