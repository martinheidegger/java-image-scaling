/*
 * Copyright 2009, Morten Nobel-Joergensen
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.mortennobel.imagescaling;

import java.awt.image.BufferedImage;
import java.awt.image.DataBuffer;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Based on work from Java Image Util ( http://schmidt.devlib.org/jiu/ )
 *
 * Note that the filter method is not thread safe
 *
 * @author Morten Nobel-Joergensen
 * @author Heinz Doerr
 */
public class ResampleOp extends AdvancedResizeOp
{
	private final int MAX_CHANNEL_VALUE= 255;

	private int amountChannels;
	private int srcWidth;
	private int srcHeight;
	private int dstWidth;
	private int dstHeight;

	static class SubSamplingData{
		private final int[] contributionsPerPixels; // individual - per row or per column - nr of contributions
		private final int[] pickPixels;  // 2Dim: [wid or hei][contrib]
		private final float[] weights; // 2Dim: [wid or hei][contrib]
		private final int numContributors; // the primary index length for the 2Dim arrays : arrPixel and arrWeight

		private SubSamplingData(ResampleFilter filter, int srcSize, int dstSize) {
			if( srcSize == 0 ) {
				throw new RuntimeException("Can not sample a image with size == 0");
			}
			if( dstSize == 0 ) {
				throw new RuntimeException("Can not sample a image to the size == 0");
			}
			
			contributionsPerPixels = new int[dstSize];
			
			float scale = (float)dstSize / (float)srcSize;
			final float rawFilterSize = filter.getSamplingRadius();
			
			float filterSize;
			float filterNormalization;
			int excessContributors;
			if (scale < 1.0f) {
				filterSize = rawFilterSize / scale;
				filterNormalization= (float)(1f / (Math.ceil(filterSize) / rawFilterSize));
				excessContributors = 2; // Heinz: added 1 to be save with the ceilling
			} else {
				filterSize = rawFilterSize;
				filterNormalization = 1.0f;
				excessContributors = 1;
			}
			
			numContributors= (int)(filterSize * 2 + excessContributors);
			weights = new float[dstSize * numContributors];
			pickPixels = new int[dstSize * numContributors];
			
			float pickStep = 1.0f/scale;
			float pickPixelCenter = 0.5f/scale;
			int pixel = 0;
			while( pixel < dstSize ) {
				final int subindex = pixel * numContributors;
				float totalWeight = 0.0f;
				int currentPixel = (int)(pickPixelCenter - filterSize); // automatic rounding down
				int endPixel = (int)(pickPixelCenter + filterSize + 1.0); // automatic rounding up
				for (; currentPixel <= endPixel; currentPixel++) {
					float weight = filter.apply((pickPixelCenter - currentPixel) * filterNormalization);
					if (weight != 0.0f) {
						int pickPixel;
						if (currentPixel < 0) {
							pickPixel = -currentPixel;
						} else if (currentPixel >= srcSize) {
							pickPixel = srcSize - currentPixel + srcSize - 1;
						} else {
							pickPixel = currentPixel;
						}
						if (pickPixel < 0 && pickPixel >= srcSize) {
							weight = 0.0f;
						}
						int k = contributionsPerPixels[pixel];
						contributionsPerPixels[pixel]++;
						pickPixels[subindex + k]= pickPixel;
						weights[subindex + k]= weight;
						totalWeight += weight;
					}
				}
				
				// normalize the filter's weight's so the sum equals to 1.0, very important for avoiding box type of artifacts
				final int contributionPerPixel = contributionsPerPixels[pixel];
				if (totalWeight != 0f) {
					for (int k= 0; k < contributionPerPixel; k++)
						weights[subindex + k] /= totalWeight;
				}
				
				pickPixelCenter += pickStep;
				pixel++;
			}
		}
		
		public int getNumContributors() {
			return numContributors;
		}
		
		public int[] getArrN() {
			return contributionsPerPixels;
		}
		
		public int[] getArrPixel() {
			return pickPixels;
		}
		
		public float[] getArrWeight() {
			return weights;
		}
	}

	private SubSamplingData horizontalSubsamplingData;
	private SubSamplingData verticalSubsamplingData;

	private int processedItems;
	private float totalItems;

	private int numberOfThreads = Runtime.getRuntime().availableProcessors();

	private AtomicInteger multipleInvocationLock = new AtomicInteger();

	private ResampleFilter filter = ResampleFilters.getLanczos3Filter();


	public ResampleOp(int destWidth, int destHeight) {
		this(DimensionConstrain.createAbsolutionDimension(destWidth, destHeight));
	}

	public ResampleOp(DimensionConstrain dimensionConstrain) {
		super(dimensionConstrain);
	}

	public ResampleFilter getFilter() {
		return filter;
	}

	public void setFilter(ResampleFilter filter) {
		this.filter = filter;
	}

	public int getNumberOfThreads() {
		return numberOfThreads;
	}

	public void setNumberOfThreads(int numberOfThreads) {
		this.numberOfThreads = numberOfThreads;
	}

	public BufferedImage doFilter(BufferedImage srcImg, BufferedImage dest, int dstWidth, int dstHeight) {
		
		assert multipleInvocationLock.incrementAndGet()==1:"Multiple concurrent invocations detected";
		
		if (dstWidth<3 || dstHeight<3){
			throw new RuntimeException("Error doing rescale. Target size was "+dstWidth+"x"+dstHeight+" but must be at least 3x3.");
		}
		
		srcImg = assertProcessableImageType(srcImg);
		
		amountChannels = ImageUtils.nrChannels(srcImg);
		assert amountChannels > 0;
		
		this.dstWidth = dstWidth;
		this.dstHeight = dstHeight;
		
		srcWidth = srcImg.getWidth();
		srcHeight = srcImg.getHeight();
		
		byte[][] workPixels = new byte[srcHeight][dstWidth*amountChannels];
		
		this.processedItems = 0;
		this.totalItems = srcHeight + dstWidth;

		// Pre-calculate  sub-sampling
		horizontalSubsamplingData = new SubSamplingData(filter, srcWidth, dstWidth);
		verticalSubsamplingData = new SubSamplingData(filter,srcHeight, dstHeight);
		
		final BufferedImage scrImgCopy = srcImg;
		final byte[][] workPixelsCopy = workPixels;
		Thread[] threads = new Thread[numberOfThreads-1];
		for (int i=1;i<numberOfThreads;i++){
			final int finalI = i;
			threads[i-1] = new Thread(new Runnable(){
				public void run(){
					horizontallyFromSrcToWork(scrImgCopy, workPixelsCopy,finalI,numberOfThreads);
				}
			});
			threads[i-1].start();
		}
		horizontallyFromSrcToWork(scrImgCopy, workPixelsCopy,0,numberOfThreads);
		waitForAllThreads(threads);

		byte[] outPixels = new byte[dstWidth*dstHeight*amountChannels];
		// --------------------------------------------------
		// Apply filter to sample vertically from Work to Dst
		// --------------------------------------------------
		final byte[] outPixelsCopy = outPixels;
		for (int i=1;i<numberOfThreads;i++){
			final int finalI = i;
			threads[i-1] = new Thread(new Runnable(){
				public void run(){
					verticalFromWorkToDst(workPixelsCopy, outPixelsCopy, finalI,numberOfThreads);
				}
			});
			threads[i-1].start();
		}
		verticalFromWorkToDst(workPixelsCopy, outPixelsCopy, 0,numberOfThreads);
		waitForAllThreads(threads);

		//noinspection UnusedAssignment
		workPixels = null; // free memory
		BufferedImage out;
		if (dest!=null && dstWidth==dest.getWidth() && dstHeight==dest.getHeight()){
			out = dest;
			int nrDestChannels = ImageUtils.nrChannels(dest);
			if (nrDestChannels != amountChannels){
				String errorMgs = String.format("Destination image must be compatible width source image. Source image had %d channels destination image had %d channels", amountChannels, nrDestChannels);
				throw new RuntimeException(errorMgs);
			}
		}else{
			out = new BufferedImage(dstWidth, dstHeight, getResultBufferedImageType(srcImg));
		}

		ImageUtils.setBGRPixels(outPixels, out, 0, 0, dstWidth, dstHeight);

		assert multipleInvocationLock.decrementAndGet()==0:"Multiple concurrent invocations detected";

		return out;
	}

	private BufferedImage assertProcessableImageType(BufferedImage srcImg) {
		if (isNotProcessableImageType(srcImg)) {
			return ImageUtils.convert(srcImg, srcImg.getColorModel().hasAlpha() ?
					BufferedImage.TYPE_4BYTE_ABGR : BufferedImage.TYPE_3BYTE_BGR);
		} else {
			return srcImg;
		}
	}

	private boolean isNotProcessableImageType(BufferedImage srcImg) {
		return srcImg.getType() == BufferedImage.TYPE_BYTE_BINARY ||
			srcImg.getType() == BufferedImage.TYPE_BYTE_INDEXED ||
			srcImg.getType() == BufferedImage.TYPE_CUSTOM;
	}

	private void waitForAllThreads(Thread[] threads) {
		try {
			for (Thread t:threads){
				t.join(Long.MAX_VALUE);
			}
		} catch (InterruptedException e) {
			e.printStackTrace();
			throw new RuntimeException(e);
		}
	}

	static SubSamplingData createSubSampling(ResampleFilter filter, int srcSize, int dstSize) {
		return new SubSamplingData(filter, srcSize, dstSize);
	}

	private void verticalFromWorkToDst(byte[][] workPixels, byte[] outPixels, int start, int delta) {
		if (amountChannels==1){
			verticalFromWorkToDstGray(workPixels, outPixels, start,numberOfThreads);
			return;
		}
		boolean useChannel3 = amountChannels>3;
		for (int x = start; x < dstWidth; x+=delta)
		{
			final int xLocation = x*amountChannels;
			for (int y = dstHeight-1; y >=0 ; y--)
			{
				final int yTimesNumContributors = y * verticalSubsamplingData.numContributors;
				final int max= verticalSubsamplingData.contributionsPerPixels[y];
				final int sampleLocation = (y*dstWidth+x)*amountChannels;


				float sample0 = 0.0f;
				float sample1 = 0.0f;
				float sample2 = 0.0f;
				float sample3 = 0.0f;
				int index= yTimesNumContributors;
				for (int j= max-1; j >=0 ; j--) {
					int valueLocation = verticalSubsamplingData.pickPixels[index];
					float arrWeight = verticalSubsamplingData.weights[index];
					sample0+= (workPixels[valueLocation][xLocation]&0xff) *arrWeight ;
					sample1+= (workPixels[valueLocation][xLocation+1]&0xff) * arrWeight;
					sample2+= (workPixels[valueLocation][xLocation+2]&0xff) * arrWeight;
					if (useChannel3){
						sample3+= (workPixels[valueLocation][xLocation+3]&0xff) * arrWeight;
					}

					index++;
				}

				outPixels[sampleLocation] = toByte(sample0);
				outPixels[sampleLocation +1] = toByte(sample1);
				outPixels[sampleLocation +2] = toByte(sample2);
				if (useChannel3){
					outPixels[sampleLocation +3] = toByte(sample3);
				}

			}
			processedItems++;
			if (start==0){ // only update progress listener from main thread
				setProgress();
			}
		}
	}

	private void verticalFromWorkToDstGray(byte[][] workPixels, byte[] outPixels, int start, int delta) {
		for (int x = start; x < dstWidth; x+=delta)
		{
			final int xLocation = x;
			for (int y = dstHeight-1; y >=0 ; y--)
			{
				final int yTimesNumContributors = y * verticalSubsamplingData.numContributors;
				final int max= verticalSubsamplingData.contributionsPerPixels[y];
				final int sampleLocation = (y*dstWidth+x);


				float sample0 = 0.0f;
				int index= yTimesNumContributors;
				for (int j= max-1; j >=0 ; j--) {
					int valueLocation = verticalSubsamplingData.pickPixels[index];
					float arrWeight = verticalSubsamplingData.weights[index];
					sample0+= (workPixels[valueLocation][xLocation]&0xff) *arrWeight ;

					index++;
				}

				outPixels[sampleLocation] = toByte(sample0);
			}
			processedItems++;
			if (start==0){ // only update progress listener from main thread
				setProgress();
			}
		}
	}

	/**
	 * Apply filter to sample horizontally from Src to Work
	 * @param srcImg
	 * @param workPixels
	 */
	private void horizontallyFromSrcToWork(BufferedImage srcImg, byte[][] workPixels, int start, int delta) {
		if (amountChannels==1){
			horizontallyFromSrcToWorkGray(srcImg, workPixels, start, delta);
			return;
		}
		final int[] tempPixels = new int[srcWidth];   // Used if we work on int based bitmaps, later used to keep channel values
		final byte[] srcPixels = new byte[srcWidth*amountChannels]; // create reusable row to minimize memory overhead
		final boolean useChannel3 = amountChannels>3;


		for (int k = start; k < srcHeight; k=k+delta)
		{
			ImageUtils.getPixelsBGR(srcImg, k, srcWidth, srcPixels, tempPixels);

			for (int i = dstWidth-1;i>=0 ; i--)
			{
				int sampleLocation = i*amountChannels;
				final int max = horizontalSubsamplingData.contributionsPerPixels[i];

				float sample0 = 0.0f;
				float sample1 = 0.0f;
				float sample2 = 0.0f;
				float sample3 = 0.0f;
				int index= i * horizontalSubsamplingData.numContributors;
				for (int j= max-1; j >= 0; j--) {
					float arrWeight = horizontalSubsamplingData.weights[index];
					int pixelIndex = horizontalSubsamplingData.pickPixels[index]*amountChannels;

					sample0 += (srcPixels[pixelIndex]&0xff) * arrWeight;
					sample1 += (srcPixels[pixelIndex+1]&0xff) * arrWeight;
					sample2 += (srcPixels[pixelIndex+2]&0xff)  * arrWeight;
					if (useChannel3){
						sample3 += (srcPixels[pixelIndex+3]&0xff)  * arrWeight;
					}
					index++;
				}

				workPixels[k][sampleLocation] = toByte(sample0);
				workPixels[k][sampleLocation +1] = toByte(sample1);
				workPixels[k][sampleLocation +2] = toByte(sample2);
				if (useChannel3){
					workPixels[k][sampleLocation +3] = toByte(sample3);
				}
			}
			processedItems++;
			if (start==0){ // only update progress listener from main thread
				setProgress();
			}
		}
	}

	/**
	 * Apply filter to sample horizontally from Src to Work
	 * @param srcImg
	 * @param workPixels
	 */
	private void horizontallyFromSrcToWorkGray(BufferedImage srcImg, byte[][] workPixels, int start, int delta) {
		final int[] tempPixels = new int[srcWidth];   // Used if we work on int based bitmaps, later used to keep channel values
		final byte[] srcPixels = new byte[srcWidth]; // create reusable row to minimize memory overhead

		for (int k = start; k < srcHeight; k=k+delta)
		{
			ImageUtils.getPixelsBGR(srcImg, k, srcWidth, srcPixels, tempPixels);

			for (int i = dstWidth-1;i>=0 ; i--)
			{
				int sampleLocation = i;
				final int max = horizontalSubsamplingData.contributionsPerPixels[i];

				float sample0 = 0.0f;
				int index= i * horizontalSubsamplingData.numContributors;
				for (int j= max-1; j >= 0; j--) {
					float arrWeight = horizontalSubsamplingData.weights[index];
					int pixelIndex = horizontalSubsamplingData.pickPixels[index];

					sample0 += (srcPixels[pixelIndex]&0xff) * arrWeight;
					index++;
				}

				workPixels[k][sampleLocation] = toByte(sample0);
			}
			processedItems++;
			if (start==0){ // only update progress listener from main thread
				setProgress();
			}
		}
	}

	private byte toByte(float f){
		if (f<0){
			return 0;
		}
		if (f>MAX_CHANNEL_VALUE){
			return (byte) MAX_CHANNEL_VALUE;
		}
		return (byte)(f+0.5f); // add 0.5 same as Math.round
	}

	private void setProgress(){
		fireProgressChanged(processedItems/totalItems);
	}

	protected int getResultBufferedImageType(BufferedImage srcImg) {
		return amountChannels == 3 ? BufferedImage.TYPE_3BYTE_BGR :
			(amountChannels == 4 ? BufferedImage.TYPE_4BYTE_ABGR :
				(srcImg.getSampleModel().getDataType() == DataBuffer.TYPE_USHORT ?
						BufferedImage.TYPE_USHORT_GRAY : BufferedImage.TYPE_BYTE_GRAY));
	}
}

