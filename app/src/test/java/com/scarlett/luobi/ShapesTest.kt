package com.scarlett.luobi
import androidx.compose.foundation.shape.RoundedCornerShape
import org.junit.Assert.assertTrue
import org.junit.Test
class ShapesTest {
    /** 1.7 硬规则：所有界面形状都是凸形系统圆角（Outline.Rounded），不再用自定义路径 */
    @Test fun allShapesConvexRounded() {
        listOf(Sq.xs, Sq.s, Sq.m, Sq.l, Sq.xl, Sq.full, Sq.rightS, Sq.topXl).forEach { assertTrue(it is RoundedCornerShape) }
    }
}
