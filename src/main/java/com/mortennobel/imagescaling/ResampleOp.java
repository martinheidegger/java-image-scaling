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

	private int nrChannels;
	private int srcWidth;
	private int srcHeight;
	private int dstWidth;
	private int dstHeight;
	
	static class SubSamplingData {
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
				int endPixel = (int)(pickPixelCenter + filterSize + 1.0f); // automatic rounding up
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
						int contributionOffset = contributionsPerPixels[pixel];
						contributionsPerPixels[pixel]++;
						pickPixels[subindex + contributionOffset]= pickPixel;
						weights[subindex + contributionOffset]= weight;
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
	
	static SubSamplingData createSubSampling(ResampleFilter filter, int srcSize, int dstSize) {
		return new SubSamplingData(filter, srcSize, dstSize);
	}
	
	private SubSamplingData horizontalSubsamplingData;
	private SubSamplingData verticalSubsamplingData;

	private AtomicInteger processedItems;
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
	
	public BufferedImage doFilter(BufferedImage srcImg, float scale) {
		int dstWidth = (int) (srcImg.getWidth()*scale+0.5f);
		int dstHeight = (int) (srcImg.getHeight()*scale+0.5f);
		return doFilter(srcImg, dstWidth, dstHeight);
	}
	
	public BufferedImage doFilter(BufferedImage srcImg, int dstWidth, int dstHeight) {
		return doFilter(srcImg, new BufferedImage(dstWidth, dstHeight, getResultBufferedImageType(srcImg)), dstWidth, dstHeight);
	}
	
	public BufferedImage doFilter(BufferedImage srcImg, BufferedImage dstImg) {
		return doFilter(srcImg, dstImg, dstImg.getWidth(), dstImg.getHeight());
	}
	
	public BufferedImage doFilter(BufferedImage srcImg, BufferedImage dstImg, int dstWidth, int dstHeight) {
		
		assert multipleInvocationLock.incrementAndGet()==1:"Multiple concurrent invocations detected";
		
		if (dstWidth<3 || dstHeight<3){
			throw new RuntimeException("Error doing rescale. Target size was "+dstWidth+"x"+dstHeight+" but must be at least 3x3.");
		}
		
		srcImg = validateImage(srcImg);
		
		nrChannels = ImageUtils.nrChannels(srcImg);
		
		assert nrChannels > 0;
		
		dstImg = validateDestinationImage(srcImg, dstImg, dstWidth, dstHeight, nrChannels);
		
		this.dstWidth = dstWidth;
		this.dstHeight = dstHeight;
		srcWidth = srcImg.getWidth();
		srcHeight = srcImg.getHeight();
		processedItems = new AtomicInteger(0);
		totalItems = srcHeight + dstWidth;
		
		horizontalSubsamplingData = new SubSamplingData(filter, srcWidth, dstWidth);
		verticalSubsamplingData = new SubSamplingData(filter, srcHeight, dstHeight);
		
		processFilter(srcImg, dstImg);
		
		assert multipleInvocationLock.decrementAndGet()==0:"Multiple concurrent invocations detected";
		
		return dstImg;
	}
	
	private void processFilter(BufferedImage srcImg, BufferedImage dstImg) {
		Thread progressThread = new Thread(new Runnable() { public void run() { checkProgress(); } });
		progressThread.setName("Progress Thread");
		progressThread.start();
		
		byte[] outPixels = processRemainingVerticalLines(
			processHorizontalLines(srcImg, new byte[srcHeight][dstWidth*nrChannels]),
			new byte[dstHeight*dstWidth*nrChannels]
		);
		
		progressThread.interrupt();
		
		ImageUtils.setBGRPixels(outPixels, dstImg, 0, 0, dstWidth, dstHeight);
	}
	
	private void checkProgress() {
		try {
			int itemsProcessedBefore = 0;
			int itemsProcessed;
			while( (itemsProcessed = processedItems.intValue()) != totalItems ) {
				if( itemsProcessed != itemsProcessedBefore ) {
					itemsProcessedBefore = itemsProcessed;
					fireProgressChanged(itemsProcessed/totalItems);
				}
				Thread.sleep(10);
			}
		} catch (InterruptedException e) {}
	}
	
	private BufferedImage validateImage(BufferedImage image) {
		if( isNotProcessable(image) ) {
			return convertToProcessable(image);
		} else {
			return image;
		}
	}
	
	private boolean isNotProcessable(BufferedImage srcImg) {
		return srcImg.getType() == BufferedImage.TYPE_BYTE_BINARY ||
			srcImg.getType() == BufferedImage.TYPE_BYTE_INDEXED ||
			srcImg.getType() == BufferedImage.TYPE_CUSTOM;
	}
	
	private BufferedImage convertToProcessable(BufferedImage srcImg) {
		return ImageUtils.convert(srcImg, srcImg.getColorModel().hasAlpha() ? BufferedImage.TYPE_4BYTE_ABGR : BufferedImage.TYPE_3BYTE_BGR);
	}

	private BufferedImage validateDestinationImage(BufferedImage srcImg,
			BufferedImage dstImg, int dstWidth, int dstHeight, int nrChannels) {
		
		if (dstImg!=null && dstWidth==dstImg.getWidth() && dstHeight==dstImg.getHeight()) {
			dstImg = validateImage(dstImg);
			int nrDestChannels = ImageUtils.nrChannels(dstImg);
			if (nrDestChannels != nrChannels){
				String errorMgs = String.format("Destination image must be compatible width source image. Source image had %d channels destination image had %d channels", nrChannels, nrDestChannels);
				throw new RuntimeException(errorMgs);
			}
		} else {
			dstImg = new BufferedImage(dstWidth, dstHeight, getResultBufferedImageType(srcImg));
		}
		return dstImg;
	}
	
	private byte[][] processHorizontalLines(final BufferedImage srcImg, final byte[][] workPixels) {
		Thread[] threads = new Thread[numberOfThreads];
		for(int index=0; index < numberOfThreads; index++) {
			threads[index] = spawnHorizontalThread(srcImg, workPixels, index);
		}
		waitForAll(threads);
		return workPixels;
	}
	
	private Thread spawnHorizontalThread(final BufferedImage srcImg, final byte[][] workPixels, final int index) {
		Thread thread = new Thread(new Runnable(){
			public void run(){
				horizontallyFromSrcToWork(srcImg, workPixels, index, numberOfThreads);
			}
		});
		thread.setName("Horizontal Runner #"+index);
		thread.start();
		return thread;
	}
	
	private void waitForAll(Thread[] threads) {
		try {
			for(Thread thread:threads) {
				thread.join(Long.MAX_VALUE);
			}
		} catch (InterruptedException e) {
 			e.printStackTrace();
 			throw new RuntimeException(e);
 		}
	}
	
	private void horizontallyFromSrcToWork(BufferedImage srcImg, byte[][] workPixels, int start, int delta) {
		if (nrChannels==1){
			horizontallyFromSrcToWorkGrayScale(srcImg, workPixels, start, delta);
		} else {
			horizontallyFromSrcToWorkRGB(srcImg, workPixels, start, delta);
		}
	}

	private void horizontallyFromSrcToWorkRGB(BufferedImage srcImg,
			byte[][] workPixels, int start, int delta) {
		final int[] tempPixels = new int[srcWidth];   // Used if we work on int based bitmaps, later used to keep channel values
		final byte[] srcPixels = new byte[srcWidth*nrChannels]; // create reusable row to minimize memory overhead
		final boolean useChannel3 = nrChannels>3;
		
		for (int k = start; k < srcHeight; k=k+delta)
		{
			ImageUtils.getPixelsBGR(srcImg, k, srcWidth, srcPixels, tempPixels);

			for (int i = dstWidth-1;i>=0 ; i--)
			{
				int sampleLocation = i*nrChannels;
				final int max = horizontalSubsamplingData.contributionsPerPixels[i];
				
				float sample0 = 0.0f;
				float sample1 = 0.0f;
				float sample2 = 0.0f;
				float sample3 = 0.0f;
				int index= i * horizontalSubsamplingData.numContributors;
				for (int j= max-1; j >= 0; j--) {
					float arrWeight = horizontalSubsamplingData.weights[index];
					int pixelIndex = horizontalSubsamplingData.pickPixels[index]*nrChannels;
					
					sample0 += (srcPixels[pixelIndex]&0xff) * arrWeight;
					sample1 += (srcPixels[pixelIndex+1]&0xff) * arrWeight;
					sample2 += (srcPixels[pixelIndex+2]&0xff) * arrWeight;
					if (useChannel3){
						sample3 += (srcPixels[pixelIndex+3]&0xff) * arrWeight;
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
			processedItems.incrementAndGet();
		}
	}
	
	private void horizontallyFromSrcToWorkGrayScale(BufferedImage srcImg, byte[][] workPixels, int start, int delta) {
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
			processedItems.incrementAndGet();
		}
	}
	
	private byte[] processRemainingVerticalLines(final byte[][] workPixels, final byte[] outPixels) {
		Thread[] threads = new Thread[numberOfThreads];
		for (int index=0; index<numberOfThreads; index++){
			threads[index] = spawnVerticalThread(workPixels, outPixels, index);
		}
		waitForAll(threads);
		return outPixels;
	}
	
	private Thread spawnVerticalThread(final byte[][] workPixels, final byte[] outPixels, final int index) {
		Thread thread = new Thread(new Runnable(){
			public void run(){
				verticalFromWorkToDst(workPixels, outPixels, index, numberOfThreads);
			}
		});
		thread.setName("Vertical Runner #"+index);
		thread.start();
		return thread;
	}

	private void verticalFromWorkToDst(byte[][] workPixels, byte[] outPixels, int start, int delta) {
		if (nrChannels==1){
			verticalFromWorkToDstGrayScale(workPixels, outPixels, start, numberOfThreads);
		} else {
			verticalFromWorkToDstRGB(workPixels, outPixels, start, delta);
		}
	}
	
	private void verticalFromWorkToDstRGB(byte[][] workPixels, byte[] outPixels, int start, int delta) {
		boolean useChannel3 = nrChannels>3;
		for (int x = start; x < dstWidth; x+=delta)
		{
			final int xLocation = x*nrChannels;
			for (int y = dstHeight-1; y >=0 ; y--)
			{
				final int yTimesNumContributors = y * verticalSubsamplingData.numContributors;
				final int max = verticalSubsamplingData.contributionsPerPixels[y];
				final int sampleLocation = (y*dstWidth+x)*nrChannels;
				
				float sample0 = 0.0f;
				float sample1 = 0.0f;
				float sample2 = 0.0f;
				float sample3 = 0.0f;
				int index = yTimesNumContributors;
				for (int j = max-1; j >= 0 ; j--) {
					int valueLocation = verticalSubsamplingData.pickPixels[index];
					float arrWeight = verticalSubsamplingData.weights[index];
					sample0 += (workPixels[valueLocation][xLocation]&0xff) *arrWeight ;
					sample1 += (workPixels[valueLocation][xLocation+1]&0xff) * arrWeight;
					sample2 += (workPixels[valueLocation][xLocation+2]&0xff) * arrWeight;
					if (useChannel3){
						sample3 += (workPixels[valueLocation][xLocation+3]&0xff) * arrWeight;
					}
					
					index++;
				}
				
				outPixels[sampleLocation] = toByte(sample0);
				outPixels[sampleLocation+1] = toByte(sample1);
				outPixels[sampleLocation+2] = toByte(sample2);
				if (useChannel3){
					outPixels[sampleLocation+3] = toByte(sample3);
				}
			}
			processedItems.incrementAndGet();
		}
	}

	private void verticalFromWorkToDstGrayScale(byte[][] workPixels, byte[] outPixels, int start, int delta) {
		for (int x = start; x < dstWidth; x+=delta)
		{
			final int xLocation = x;
			for (int y = dstHeight-1; y >=0 ; y--)
			{
				final int yTimesNumContributors = y * verticalSubsamplingData.numContributors;
				final int max = verticalSubsamplingData.contributionsPerPixels[y];
				final int sampleLocation = (y*dstWidth+x);
				
				float sample0 = 0.0f;
				int index = yTimesNumContributors;
				for (int j = max-1; j >= 0 ; j--) {
					int valueLocation = verticalSubsamplingData.pickPixels[index];
					float arrWeight = verticalSubsamplingData.weights[index];
					sample0 += (workPixels[valueLocation][xLocation]&0xff) * arrWeight;
					index++;
				}
				
				outPixels[sampleLocation] = toByte(sample0);
			}
			processedItems.incrementAndGet();
		}
	}
	
	private byte toByte(float f){
		if (f<0) {
			return 0;
		} else if (f>MAX_CHANNEL_VALUE) {
			return (byte) MAX_CHANNEL_VALUE;
		} else {
			return (byte)(f+0.5f); // add 0.5 same as Math.round
		}
	}
	
	protected int getResultBufferedImageType(BufferedImage srcImg) {
		return nrChannels == 3 ? BufferedImage.TYPE_3BYTE_BGR :
			  (nrChannels == 4 ? BufferedImage.TYPE_4BYTE_ABGR :
			  (srcImg.getSampleModel().getDataType() == DataBuffer.TYPE_USHORT ?
			      BufferedImage.TYPE_USHORT_GRAY :
			      BufferedImage.TYPE_BYTE_GRAY)
		);
	}
}
