package com.mortennobel.imagescaling;

import java.awt.image.BufferedImage;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Issue with incorrect fraction from progressListener
 */
public class Issue8 {
	float min = 1;
	float max = 0;
	float lastProgress = 0;

	@Test
	public void progressListenerTest() {
		BufferedImage img = new BufferedImage(400,250, BufferedImage.TYPE_INT_RGB);
		ResampleOp resizeOp = new ResampleOp(DimensionConstrain.createMaxDimension(300, 300, true));
		resizeOp.addProgressListener(new ProgressListener() {
			public void notifyProgress(float fraction) {
				assertTrue(String.format("%f", fraction), fraction>=0 );
				assertTrue(String.format("%f", fraction), fraction<=1 );
				min = Math.min(min, fraction);
				max = Math.max(max, fraction);
				assertTrue(String.format("%f<=%f", lastProgress, fraction), lastProgress<=fraction);
				lastProgress = fraction;
			}
		});
		resizeOp.filter(img, null);
		assertTrue("There should be a fraction that is smaller than 0.01 but the smallest is:" + min, min < 0.01f);
		assertTrue("There should be a fraction that is bigger than 0.99 but the biggest is:" + max, max > 0.99f);		 
	}
}
