package io.github.shigella520.linkpeek.core.media;

import org.junit.jupiter.api.Test;

import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TitleCardRendererTest {
    private static final int AVAILABLE_WIDTH = 1016;
    private static final int AVAILABLE_HEIGHT = 486;

    @Test
    void cjkTitleCanBreakAfterCharactersInsteadOfStoppingAtEarlyPunctuation() {
        String earlyClause = "考上985后才明白，";
        String title = earlyClause + "专业选择和地理环境远比较名重要，普通大学的优质专业与一线城市资源更能决定未来";

        BufferedImage image = new BufferedImage(TitleCardRenderer.WIDTH, TitleCardRenderer.HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            TitleCardRenderer.TextLayout layout = TitleCardRenderer.fitTitleLayout(
                    graphics,
                    title,
                    AVAILABLE_WIDTH,
                    AVAILABLE_HEIGHT
            );

            FontMetrics metrics = graphics.getFontMetrics(layout.font());
            assertTrue(layout.lines().size() <= 3);
            assertNotEquals(earlyClause, layout.lines().get(0));
            for (String line : layout.lines()) {
                assertTrue(metrics.stringWidth(line) <= AVAILABLE_WIDTH);
            }
        } finally {
            graphics.dispose();
        }
    }
}
