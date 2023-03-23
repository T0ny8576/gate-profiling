package edu.cmu.cs.owf;

import android.graphics.Bitmap;
import android.widget.ImageView;

public class ML2CameraCapture {
    private final ImageView imagePreview;
    private final int width;
    private final int height;
    public ML2CameraCapture(int width, int height, ImageView imageView) {
        imagePreview = imageView;
        width = Integer.min(1920, width);
        height = Integer.min(1080, height);
        this.width = width;
        this.height = height;
        createCamera(width, height);
    }

    private Bitmap createArgbBitmap(byte[] rgbaBytes, int width, int height) {
        int size = rgbaBytes.length;
        int i = 0;
        int colorIndex = 0;
        int[] colors = new int[size / 4];
        while (i + 4 <= size) {
            int r = rgbaBytes[i];
            int g = rgbaBytes[i + 1];
            int b = rgbaBytes[i + 2];
            int a = rgbaBytes[i + 3];
            colors[colorIndex] = (a & 0xff) << 24 | (r & 0xff) << 16 | (g & 0xff) << 8 | (b & 0xff);
            i += 4;
            colorIndex++;
        }
        return Bitmap.createBitmap(colors, width, height, Bitmap.Config.ARGB_8888);
    }
    public Bitmap updateImagePreview() {
        byte[] rgbaFrame = getFrame(width * height);
        if (rgbaFrame == null) {
            return null;
        }
        Bitmap bitmap = createArgbBitmap(rgbaFrame, width, height);
        imagePreview.post(()->imagePreview.setImageBitmap(bitmap));
        return bitmap;
    }

    public void shutdown() {
        stopCamera();
    }

    private native void createCamera(int width, int height);

    private native byte[] getFrame(int size);

    private native void stopCamera();

    static {
        System.loadLibrary("ml2_api_lib");
    }
}