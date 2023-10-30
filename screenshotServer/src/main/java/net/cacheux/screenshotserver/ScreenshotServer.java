package net.cacheux.screenshotserver;

import android.annotation.SuppressLint;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.Build;
import android.os.IBinder;
import android.os.IInterface;
import android.util.Log;
import android.view.View;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileWriter;
import java.io.InputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

@SuppressLint("PrivateApi")
public class ScreenshotServer {
    private static final String TAG = ScreenshotServer.class.getSimpleName();
    private static final int DEFAULT_PORT = 57000;
//	private static final int BYTES_PER_PIXEL = 2; // RGB565
	private static final int BYTES_PER_PIXEL = 4; // ARGB8888

	private static final Class<?> CLASS;
    private final IInterface displayService;
	private static Method getBuiltInDisplayMethod;

    private int port;

    private ServerSocket serverSocket;
    private AtomicBoolean running = new AtomicBoolean(false);

	static {
		try {
			CLASS = Class.forName("android.view.SurfaceControl");
		} catch (ClassNotFoundException e) {
			throw new AssertionError(e);
		}
	}

    private ScreenshotServer(int port) {
        this.port = port;

        try {
            @SuppressLint("DiscouragedPrivateApi")
            Method getService = Class.forName("android.os.ServiceManager")
                    .getDeclaredMethod("getService", String.class);
            IBinder binder = (IBinder) getService.invoke(null, "display");
            Method asInterface = Class.forName("android.hardware.display.IDisplayManager$Stub")
                    .getMethod("asInterface", IBinder.class);
            displayService = (IInterface) asInterface.invoke(null, binder);
        } catch (NoSuchMethodException | ClassNotFoundException | IllegalAccessException | InvocationTargetException e) {
            Log.e(TAG, "Error getting Android services", e);
            throw new IllegalStateException("Error getting Android services", e);
        }
    }

    private void startServer() {
        Log.i(TAG, "Starting ScreenshotServer on port " + port);

        running.set(true);
        try {
            serverSocket = new ServerSocket(port);
            do {
                Socket socket = serverSocket.accept();
                ClientThread clientThread = new ClientThread(socket);
                clientThread.start();
            } while (running.get());
        } catch (IOException e) {
            Log.e(TAG, "Error with server socket", e);
        }
    }

    private void stopServer() {
        Log.i(TAG, "Stopping ScreenshotServer");

        running.set(false);
        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (IOException e) {
                Log.e(TAG, "Error closing server socket", e);
            }
        }
    }

    public static void main(String[] args) {
        File pidFile = new File("/data/local/tmp/screenshotServer.pid");

        if (pidFile.exists()) {
            pidFile.delete();
        }

        try (FileWriter fileWriter = new FileWriter(pidFile)) {
            fileWriter.write(String.valueOf(android.os.Process.myPid()));
        } catch (IOException e) {
            Log.e(TAG, "Error writing pid file", e);
        }

        int port = DEFAULT_PORT;
        if (args.length > 0) {
            try {
                port = Integer.parseInt(args[0]);
            } catch (NumberFormatException e) {
                Log.w(TAG, "Error reading port number", e);
            }
        }

        final ScreenshotServer server = new ScreenshotServer(port);
        Runtime.getRuntime().addShutdownHook(new Thread() {
            @Override
            public void run() {
                server.stopServer();
            }
        });
        server.startServer();
    }

    private class ClientThread extends Thread {
        private Socket socket;

        ClientThread(Socket socket) {
            this.socket = socket;
        }

		// ./gradlew assemble
		// cp -av  ./screenshotServer/build/outputs/apk/debug/screenshotServer-debug.apk ss.jar
		// adb -s localhost:5600 push ss.jar /sdcard/
		// adb -s localhost:5600 shell
		//
		// setprop ro.llk.blacklist.uid 10116
		//
		// CLASSPATH=/sdcard/ss.jar app_process /system/bin net.cacheux.screenshotserver.ScreenshotServer $@ &
		//
		// am start -n com.kingsgroup.sos/com.kingsgroup.ss.KGUnityPlayerActivity

        @Override
        public void run() {
            try {
				OutputStream out = socket.getOutputStream();
				InputStream in = socket.getInputStream();
				byte[] b = { 0x01 };
				while (b[0] > 0) {
					Log.d(TAG, "b: " + b[0] + " start:" + System.nanoTime());
					Bitmap bitmap = takeScreenshot();
					Log.d(TAG, "ss done:" + System.nanoTime());
					/*
					bitmap.compress(Bitmap.CompressFormat.PNG, 0, out);
					*/
					int width = bitmap.getWidth();
					int height = bitmap.getHeight();
					int bufferSize = width * height * BYTES_PER_PIXEL;
					ByteBuffer buffer = ByteBuffer.allocate(bufferSize);
//					bitmap.copy(Bitmap.Config.RGB_565, true).copyPixelsToBuffer(buffer);
					bitmap.copy(Bitmap.Config.ARGB_8888, true).copyPixelsToBuffer(buffer);
					out.write(intToBigEndian(bufferSize));
					out.write(buffer.array());
					Log.d(TAG, "compress done:" + System.nanoTime());
					if (in.read(b) < 0)
						break;
				}
                socket.close();
				Log.d(TAG, "end:" + System.nanoTime());
            } catch (IOException e) {
                Log.e(TAG, "Error with client socket", e);
            }
        }

        private Bitmap takeScreenshot() {
            Rect screenSize = getScreenSize();

            try {
				int width = screenSize.width();
				int height = screenSize.height();
				final IBinder displayToken = getBuiltInDisplay();
				Class<?> builderClass = Class.forName("android.view.SurfaceControl$DisplayCaptureArgs$Builder");
				Constructor<?> builderConstructor = builderClass.getDeclaredConstructor(IBinder.class);
				builderConstructor.setAccessible(true);
				Object builder = builderConstructor.newInstance(displayToken);
				Method sourceCropField = builderClass.getMethod("setSourceCrop", Rect.class);
				sourceCropField.invoke(builder, screenSize);

				Method sizeMethod = builderClass.getMethod("setSize", Integer.TYPE, Integer.TYPE);
				sizeMethod.setAccessible(true);
				sizeMethod.invoke(builder, width, height);

				Method build = builderClass.getMethod("build");
				build.setAccessible(true);
				Object captureArgs = build.invoke(builder);

				Class<?> captureArgsClass = Class.forName("android.view.SurfaceControl$DisplayCaptureArgs");

				Method argsMethod = CLASS.getMethod("captureDisplay", captureArgsClass);
				argsMethod.setAccessible(true);
				Object cap = argsMethod.invoke(CLASS, captureArgs);
				if (cap == null) {
					throw new Exception("Inject SurfaceControl captureDisplay return null");
				}
				Class<?> bufferClass = Class.forName("android.view.SurfaceControl$ScreenshotHardwareBuffer");
				return (Bitmap)bufferClass.getMethod("asBitmap").invoke(cap);
            } catch (Exception e) {
                Log.e(TAG, "Error taking screenshot", e);
                throw new IllegalStateException("Error taking screenshot", e);
            }
        }

        private Rect getScreenSize() {
            try {
                Object displayInfo = displayService.getClass()
                        .getMethod("getDisplayInfo", int.class)
                        .invoke(displayService, 0);
                Class<?> cls = displayInfo.getClass();
                int width = cls.getDeclaredField("logicalWidth").getInt(displayInfo);
                int height = cls.getDeclaredField("logicalHeight").getInt(displayInfo);
                return new Rect(0, 0, width, height);
            } catch (NoSuchMethodException | NoSuchFieldException | IllegalAccessException | InvocationTargetException e) {
                Log.e(TAG, "Error getting screen info", e);
                throw new IllegalStateException("Error getting screen info", e);
            }
        }
    }

	private static Method getGetBuiltInDisplayMethod() throws NoSuchMethodException {
		if (getBuiltInDisplayMethod == null) {
			// the method signature has changed in Android Q
			// <https://github.com/Genymobile/scrcpy/issues/586>
			if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
				getBuiltInDisplayMethod = CLASS.getMethod("getBuiltInDisplay", int.class);
			} else {
				getBuiltInDisplayMethod = CLASS.getMethod("getInternalDisplayToken");
			}
		}
		return getBuiltInDisplayMethod;
	}

	public static IBinder getBuiltInDisplay() {
		try {
			Method method = getGetBuiltInDisplayMethod();
			if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
				// call getBuiltInDisplay(0)
				return (IBinder) method.invoke(null, 0);
			}

			// call getInternalDisplayToken()
			return (IBinder) method.invoke(null);
		} catch (InvocationTargetException | IllegalAccessException | NoSuchMethodException e) {
			Log.e(TAG, "Could not invoke method", e);
			return null;
		}
	}

	private byte[] intToBigEndian(int a) {
		Log.i(TAG, "length:" + a);
		byte[] ret = new byte[4];
		ret[3] = (byte) (a & 0xFF);
		ret[2] = (byte) ((a >> 8) & 0xFF);
		ret[1] = (byte) ((a >> 16) & 0xFF);
		ret[0] = (byte) ((a >> 24) & 0xFF);
		return ret;
	}
}
