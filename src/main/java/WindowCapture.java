
import com.sun.jna.Memory;
import com.sun.jna.platform.win32.*;


import java.awt.image.BufferedImage;

public class WindowCapture {

    private WinDef.HWND hwnd;
    private int orgwidth;
    private int orgheight;
    private volatile boolean isRunning = false;
    private long captureInterval = 1000; // 默认捕获间隔1秒
    private CaptureCallback callback;

    public interface CaptureCallback {
        void onCaptured(BufferedImage image);
        void onError(Exception e);
    }


    public WindowCapture(String windowTitle) {
        hwnd = User32.INSTANCE.FindWindow(null, windowTitle);
        if (hwnd == null) {
            throw new RuntimeException("找不到窗口");
        }

        WinDef.RECT rect = new WinDef.RECT();
        User32.INSTANCE.GetClientRect(hwnd, rect);
        orgwidth = rect.right - rect.left;
        orgheight = rect.bottom - rect.top;
        if (orgwidth<=0 || orgheight <=0){
            throw new RuntimeException("无效的窗口尺寸"+"orgwidth="+orgwidth+"orgheight="+orgheight);
        }
    }
    // 设置捕获间隔
    public void setCaptureInterval(long milliseconds) {
        this.captureInterval = milliseconds;
    }

    // 设置回调
    public void setCaptureCallback(CaptureCallback callback) {
        this.callback = callback;
    }

    // 开始捕获
    public void startCapture() {
        if (isRunning) {
            return;
        }
        isRunning = true;

        Thread captureThread = new Thread(() -> {
            while (isRunning) {
                try {
                    BufferedImage image = capture();
                    if (callback != null) {
                        callback.onCaptured(image);
                    }
                    Thread.sleep(captureInterval);
                } catch (Exception e) {
                    if (callback != null) {
                        callback.onError(e);
                    }
                    // 如果发生错误，可以选择继续运行或停止
                    // isRunning = false;
                }
            }
        });
        captureThread.setDaemon(true); // 设置为守护线程
        captureThread.start();
    }

    // 停止捕获
    public void stopCapture() {
        isRunning = false;
    }


    public BufferedImage capture() {

        WinDef.HDC hdcWindow = null;
        WinDef.HDC hdcMemory = null;
        WinDef.HBITMAP hBitmap = null;
        WinNT.HANDLE hOld = null;
        int width,height;

        if (!User32.INSTANCE.IsWindow(hwnd)) {
            throw new RuntimeException("窗口不存在") ;
        }

        BaseTSD.LONG_PTR style = User32.INSTANCE.GetWindowLongPtr(hwnd, WinUser.GWL_STYLE);
        long windowStyle = style.longValue();

        if ((windowStyle & WinUser.WS_MINIMIZE) != 0) {
            width = orgwidth;
            height = orgheight;
            System.out.println("窗口状态: 最小化");
        }else {
            WinDef.RECT rect = new WinDef.RECT();
            User32.INSTANCE.GetClientRect(hwnd, rect);
            width = rect.right - rect.left;
            height = rect.bottom - rect.top;
            System.out.println("窗口状态:不是最小化");
        }

        hdcWindow = User32.INSTANCE.GetDC(hwnd);
        hdcMemory = GDI32.INSTANCE.CreateCompatibleDC(hdcWindow);

        hBitmap = GDI32.INSTANCE.CreateCompatibleBitmap(hdcWindow, width, height);
        hOld = GDI32.INSTANCE.SelectObject(hdcMemory, hBitmap);

        boolean success = User32.INSTANCE.PrintWindow(hwnd, hdcMemory, 0);
        if (!success) {
            throw new RuntimeException("PrintWindow failed");
        }

        WinGDI.BITMAPINFO bmi = new WinGDI.BITMAPINFO();
        bmi.bmiHeader.biWidth = width;
        bmi.bmiHeader.biHeight = -height;
        bmi.bmiHeader.biPlanes = 1;
        bmi.bmiHeader.biBitCount = 32;
        bmi.bmiHeader.biCompression = WinGDI.BI_RGB;


        Memory buffer = new Memory( width * height * 4);
        GDI32.INSTANCE.GetDIBits(
                hdcMemory,
                hBitmap,
                0,
                height,
                buffer,
                bmi,
                WinGDI.DIB_RGB_COLORS
        );

        BufferedImage image = new BufferedImage(
                width, height,
                BufferedImage.TYPE_INT_ARGB
        );

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int offset = (y * width + x) * 4;

                int blue = buffer.getByte(offset) & 0xFF;     // 蓝色分量
                int green = buffer.getByte(offset + 1) & 0xFF; // 绿色分量
                int red = buffer.getByte(offset + 2) & 0xFF;   // 红色分量
                int alpha = buffer.getByte(offset + 3) & 0xFF; // 透明度

                int pixel = (alpha << 24) | (red << 16) | (green << 8) | blue;

                image.setRGB(x, y, pixel);
            }
        }


        // 一定要按照正确的顺序释放资源
        if (hOld != null) {
            GDI32.INSTANCE.SelectObject(hdcMemory, hOld);  // 恢复原始位图
        }
        if (hBitmap != null) {
            GDI32.INSTANCE.DeleteObject(hBitmap);  // 删除位图
        }
        if (hdcMemory != null) {
            GDI32.INSTANCE.DeleteDC(hdcMemory);    // 删除内存DC
        }
        if (hdcWindow != null) {
            User32.INSTANCE.ReleaseDC(hwnd, hdcWindow);  // 释放窗口DC
        }
        return image;

    }

}
