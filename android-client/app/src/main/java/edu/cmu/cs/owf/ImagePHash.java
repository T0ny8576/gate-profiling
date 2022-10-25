package edu.cmu.cs.owf;

import android.graphics.*;

import java.util.concurrent.ConcurrentLinkedDeque;

/*
 * pHash-like image hash.
 * Author: Elliot Shepherd (elliot@jarofworms.com
 * Based On: http://www.hackerfactor.com/blog/index.php?/archives/432-Looks-Like-It.html
 */
public final class ImagePHash {
    private static final int size = 32;
    private static final int hashSize = 8;
    private static final double[] c = {1. / Math.sqrt(2.), 1., 1., 1., 1., 1., 1., 1.,
            1., 1., 1., 1., 1., 1., 1., 1.,
            1., 1., 1., 1., 1., 1., 1., 1.,
            1., 1., 1., 1., 1., 1., 1., 1.};
    ConcurrentLinkedDeque<String> logList;

    public ImagePHash(MainActivity mainActivity) {
        this.logList = mainActivity.logList;
    }

    public static long pHash(Bitmap img) {
        // 1. Reduce size
        img = resize(img, size, size);

        // 2. Reduce color
        if (img != null) {
            img = grayscale(img);
            double[][] vals = new double[size][size];
            for (int x = 0; x < img.getWidth(); x++) {
                for (int y = 0; y < img.getHeight(); y++) {
                    vals[x][y] = getBlue(img, x, y);
                }
            }

            // 3. Compute the DCT
            double[][] dctVals = applyDCT(vals);

            // 4. Reduce the DCT
            // 5. Compute the average value
            double total = 0;
            for (int x = 0; x < hashSize; x++) {
                for (int y = 0; y < hashSize; y++) {
                    total += dctVals[x][y];
                }
            }
            total -= dctVals[0][0];
            double avg = total / (double) ((hashSize * hashSize) - 1);

            // 6. Further reduce the DCT
            long hash = 0;
            for (int x = 0; x < hashSize; x++) {
                for (int y = 0; y < hashSize; y++) {
                    hash <<= 1;
                    hash += dctVals[x][y] > avg ? 1 : 0;
                }
            }
            return hash;
        } else {
            return 0;
        }
    }

    private static Bitmap resize(Bitmap bm, int newHeight, int newWidth) {
        Bitmap resizedBitmap = null;
        try {
            // Set filter parameter to true to apply bilinear filtering instead of nearest-neighbor
            // This trades performance for image quality
            // However, the python implementation uses Lanczos resampling which is even better
            resizedBitmap = Bitmap.createScaledBitmap(bm, newWidth, newHeight, true);
        } catch (NullPointerException e) {
            e.printStackTrace();
        }
        return resizedBitmap;
    }

    private static Bitmap grayscale(Bitmap orginalBitmap) {
        ColorMatrix colorMatrix = new ColorMatrix();
        colorMatrix.setSaturation(0);
        ColorMatrixColorFilter colorMatrixFilter = new ColorMatrixColorFilter(colorMatrix);
        Bitmap blackAndWhiteBitmap = orginalBitmap.copy(Bitmap.Config.ARGB_8888, true);
        Paint paint = new Paint();
        paint.setColorFilter(colorMatrixFilter);
        Canvas canvas = new Canvas(blackAndWhiteBitmap);
        canvas.drawBitmap(blackAndWhiteBitmap, 0, 0, paint);
        return blackAndWhiteBitmap;
    }

    private static int getBlue(Bitmap img, int x, int y) {
        return (img.getPixel(x, y)) & 0xff;
    }

    private static double[][] applyDCT(double[][] f) {
        int N = size;
        double[][] F = new double[N][N];
        for (int u = 0; u < N; u++) {
            for (int v = 0; v < N; v++) {
                double sum = 0.;
                for (int i = 0; i < N; i++) {
                    for (int j = 0; j < N; j++) {
                        sum += Math.cos(((2 * i + 1) / (2. * N)) * u * Math.PI) * Math.cos(((2 * j + 1) / (2. * N)) * v * Math.PI) * (f[i][j]);
                    }
                }
                sum *= ((c[u] * c[v]) / 4.);
                F[u][v] = sum;
            }
        }
        return F;
    }

    public static int distance(long hash1, long hash2) {
        long similarityMask = hash1 ^ hash2;
        return Long.bitCount(similarityMask);
    }
}