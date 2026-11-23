package com.bbf.pos;

import android.Manifest;
import android.app.DownloadManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.print.PrintAttributes;
import android.print.PrintDocumentAdapter;
import android.print.PrintManager;
import android.util.Base64;
import android.webkit.DownloadListener;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.webkit.WebViewAssetLoader;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends AppCompatActivity {

    private static final String ACTION_USB_PERMISSION = "com.bbf.pos.USB_PERMISSION";
    // Default UPI ID — agar HTML me UPI ID nahi milega toh yeh use hoga
    private static final String DEFAULT_UPI_ID = "7380792557@paytm";
    private static final String DEFAULT_PAYEE_NAME = "BIG BOY'S FASHION";

    private WebView webView;
    private String pendingPrintText = null;
    private byte[] pendingPrintBytes = null;
    private boolean usbReceiverRegistered = false;

    private final BroadcastReceiver usbPermissionReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            try {
                if (ACTION_USB_PERMISSION.equals(intent.getAction())) {
                    UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                    boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
                    if (granted && device != null) {
                        if (pendingPrintBytes != null) {
                            byte[] data = pendingPrintBytes;
                            pendingPrintBytes = null;
                            pendingPrintText = null;
                            sendBytesToUsbDevice(device, data);
                        } else if (pendingPrintText != null) {
                            String textToPrint = pendingPrintText;
                            pendingPrintText = null;
                            sendRawToUsbDevice(device, textToPrint);
                        }
                    } else {
                        pendingPrintBytes = null;
                        pendingPrintText = null;
                        Toast.makeText(MainActivity.this, "USB printer permission denied", Toast.LENGTH_SHORT).show();
                    }
                }
            } catch (Exception e) {
                pendingPrintBytes = null;
                pendingPrintText = null;
                Toast.makeText(MainActivity.this, "USB receiver error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        webView = new WebView(this);
        setContentView(webView);

        registerUsbPermissionReceiver();

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);

        final WebViewAssetLoader assetLoader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return assetLoader.shouldInterceptRequest(request.getUrl());
            }

            @Override
            @SuppressWarnings("deprecation")
            public WebResourceResponse shouldInterceptRequest(WebView view, String url) {
                return assetLoader.shouldInterceptRequest(Uri.parse(url));
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(PermissionRequest request) {
                try {
                    request.grant(request.getResources());
                } catch (Exception ignored) {}
            }
        });

        webView.addJavascriptInterface(new AndroidPOSBridge(), "AndroidPOS");

        webView.setDownloadListener(new DownloadListener() {
            @Override
            public void onDownloadStart(String url, String userAgent, String contentDisposition, String mimeType, long contentLength) {
                try {
                    if (url != null && url.startsWith("data:")) {
                        saveDataUrlToDownloads(url, mimeType);
                        return;
                    }
                    DownloadManager.Request req = new DownloadManager.Request(Uri.parse(url));
                    req.setMimeType(mimeType);
                    req.addRequestHeader("User-Agent", userAgent);
                    req.setDescription("Downloading BBF POS file...");
                    String ext = (mimeType != null && mimeType.contains("csv")) ? ".csv" : ".txt";
                    String fileName = "BBF_POS_" + System.currentTimeMillis() + ext;
                    req.setTitle(fileName);
                    req.allowScanningByMediaScanner();
                    req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                    req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName);

                    DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
                    if (dm != null) {
                        dm.enqueue(req);
                        Toast.makeText(MainActivity.this, "Downloading " + fileName, Toast.LENGTH_SHORT).show();
                    }
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "Download failed: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                }
            }
        });

        webView.loadUrl("https://appassets.androidplatform.net/assets/index.html");
    }

    private void registerUsbPermissionReceiver() {
        if (usbReceiverRegistered) return;
        try {
            IntentFilter filter = new IntentFilter(ACTION_USB_PERMISSION);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(usbPermissionReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(usbPermissionReceiver, filter);
            }
            usbReceiverRegistered = true;
        } catch (Exception ignored) {}
    }

    private void saveDataUrlToDownloads(String dataUrl, String mimeType) {
        try {
            int commaIdx = dataUrl.indexOf(',');
            if (commaIdx == -1) return;
            String base64Part = dataUrl.substring(commaIdx + 1);
            byte[] decoded = Base64.decode(base64Part, Base64.DEFAULT);
            String ext = (mimeType != null && mimeType.contains("json")) ? ".json" : ".txt";
            File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            if (!dir.exists()) dir.mkdirs();
            File outFile = new File(dir, "BBF_POS_" + System.currentTimeMillis() + ext);
            try (FileOutputStream fos = new FileOutputStream(outFile)) {
                fos.write(decoded);
            }
            Toast.makeText(this, "Saved to Downloads: " + outFile.getName(), Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "Save failed: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private UsbDevice findPrinterUsbDevice(UsbManager usbManager) {
        if (usbManager == null) return null;
        HashMap<String, UsbDevice> list = usbManager.getDeviceList();
        if (list == null || list.isEmpty()) return null;
        for (UsbDevice dev : list.values()) {
            for (int i = 0; i < dev.getInterfaceCount(); i++) {
                UsbInterface iface = dev.getInterface(i);
                if (iface.getInterfaceClass() == UsbConstants.USB_CLASS_PRINTER) {
                    return dev;
                }
            }
        }
        return list.values().iterator().next();
    }

    private byte[] buildDrawerAndCutFooter() {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try {
            // Cash drawer kick pin 2 and pin 5
            baos.write(new byte[]{0x1B, 0x70, 0x00, 0x19, (byte) 0xFA});
            baos.write(new byte[]{0x1B, 0x70, 0x01, 0x19, (byte) 0xFA});
        } catch (IOException ignored) {}
        return baos.toByteArray();
    }

    /**
     * ESC/POS Code128 Barcode bytes for dynamic Bill Number (e.g., BBF-1002)
     */
    private byte[] buildEscPosBarcode(String data) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            if (data == null || data.trim().isEmpty()) return new byte[0];
            String clean = data.trim();
            String code128Payload = "{B" + clean;
            byte[] dataBytes = code128Payload.getBytes(StandardCharsets.US_ASCII);

            // Center align
            out.write(new byte[]{0x1B, 0x61, 0x01});
            // Barcode height = 56 dots
            out.write(new byte[]{0x1D, 0x68, 0x38});
            // Barcode width = 2
            out.write(new byte[]{0x1D, 0x77, 0x02});
            // HRI text position = below barcode (2)
            out.write(new byte[]{0x1D, 0x48, 0x02});
            // Print Code128: GS k 73 (0x49) length data
            out.write(new byte[]{0x1D, 0x6B, 0x49, (byte) dataBytes.length});
            out.write(dataBytes);
            out.write("\n".getBytes(StandardCharsets.UTF_8));
            // Left align back
            out.write(new byte[]{0x1B, 0x61, 0x00});
        } catch (Exception ignored) {}
        return out.toByteArray();
    }

    /**
     * ESC/POS Native QR Code bytes (GS ( k) for dynamic UPI / Amount QR
     */
    private byte[] buildEscPosQrCode(String qrData, int moduleSize) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            if (qrData == null || qrData.trim().isEmpty()) return new byte[0];
            byte[] dataBytes = qrData.getBytes(StandardCharsets.UTF_8);
            int storeLen = dataBytes.length + 3;
            byte pL = (byte) (storeLen & 0xFF);
            byte pH = (byte) ((storeLen >> 8) & 0xFF);

            // Center align
            out.write(new byte[]{0x1B, 0x61, 0x01});

            // 1. Select QR model: Model 2
            out.write(new byte[]{0x1D, 0x28, 0x6B, 0x04, 0x00, 0x31, 0x41, 0x32, 0x00});

            // 2. Set module size (1 to 16, default 6)
            byte size = (byte) Math.max(3, Math.min(10, moduleSize));
            out.write(new byte[]{0x1D, 0x28, 0x6B, 0x03, 0x00, 0x31, 0x43, size});

            // 3. Set error correction level: M (49 = 0x31)
            out.write(new byte[]{0x1D, 0x28, 0x6B, 0x03, 0x00, 0x31, 0x45, 0x31});

            // 4. Store QR data in symbol storage area
            out.write(new byte[]{0x1D, 0x28, 0x6B, pL, pH, 0x31, 0x50, 0x30});
            out.write(dataBytes);

            // 5. Print QR code symbol
            out.write(new byte[]{0x1D, 0x28, 0x6B, 0x03, 0x00, 0x31, 0x51, 0x30});
            out.write("\n".getBytes(StandardCharsets.UTF_8));

            // Left align back
            out.write(new byte[]{0x1B, 0x61, 0x00});
        } catch (Exception ignored) {}
        return out.toByteArray();
    }

    private String extractBillNumber(String text) {
        if (text == null) return "";
        Matcher m = Pattern.compile("(?i)Bill\\s*[:#\\-]?\\s*([A-Za-z0-9\\-]+)").matcher(text);
        if (m.find()) {
            return m.group(1).trim();
        }
        return "";
    }

    private String extractGrandTotal(String text) {
        if (text == null) return "";
        Matcher m = Pattern.compile("(?i)(?:GRAND\\s*TOTAL|PAY\\s*EXACT|NET\\s*AMOUNT|TOTAL)\\s*[:]?\\s*(?:Rs\\.?|INR|₹)?\\s*([0-9]+(?:\\.[0-9]{1,2})?)").matcher(text);
        String found = "";
        while (m.find()) {
            found = m.group(1).trim();
        }
        return found;
    }

    private String extractUpiUriOrBuild(String text, String amount, String billNo) {
        if (text != null) {
            Matcher upiDirect = Pattern.compile("(upi://pay\\?[^\\s\\n]+)").matcher(text);
            if (upiDirect.find()) {
                return upiDirect.group(1).trim();
            }
            Matcher upiIdMatcher = Pattern.compile("([a-zA-Z0-9._\\-]+@[a-zA-Z0-9._\\-]+)").matcher(text);
            String upiId = DEFAULT_UPI_ID;
            if (upiIdMatcher.find()) {
                upiId = upiIdMatcher.group(1).trim();
            }
            if (amount != null && !amount.isEmpty()) {
                String note = (billNo != null && !billNo.isEmpty()) ? ("Bill%20" + Uri.encode(billNo)) : "BBF%20POS";
                return "upi://pay?pa=" + Uri.encode(upiId) + "&pn=" + Uri.encode(DEFAULT_PAYEE_NAME) + "&am=" + Uri.encode(amount) + "&cu=INR&tn=" + note;
            }
        }
        return "";
    }

    private void sendRawToUsbDevice(UsbDevice device, String text) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try {
            // Initialize printer
            baos.write(new byte[]{0x1B, 0x40});

            String billNo = extractBillNumber(text);
            String amount = extractGrandTotal(text);
            String upiPayload = extractUpiUriOrBuild(text, amount, billNo);

            // Top: Print dynamic Barcode of the current Bill Number
            if (!billNo.isEmpty()) {
                baos.write(buildEscPosBarcode(billNo));
            }

            // Middle: Print receipt text (strip any raw upi:// line if present so QR replaces it cleanly)
            if (text != null) {
                String cleanText = text.replaceAll("(?m)^.*upi://pay\\?.*\\r?\\n?", "");
                baos.write(cleanText.getBytes(StandardCharsets.UTF_8));
            }

            // Bottom: Print dynamic QR Code for exact Bill Amount (UPI QR)
            if (!upiPayload.isEmpty()) {
                baos.write("\n".getBytes(StandardCharsets.UTF_8));
                baos.write(new byte[]{0x1B, 0x61, 0x01});
                baos.write(("Scan & Pay: Rs." + amount + "\n").getBytes(StandardCharsets.UTF_8));
                baos.write(buildEscPosQrCode(upiPayload, 6));
                baos.write(new byte[]{0x1B, 0x61, 0x00});
            }

            baos.write("\n\n\n".getBytes(StandardCharsets.UTF_8));
            // Paper cut
            baos.write(new byte[]{0x1D, 0x56, 0x42, 0x00});
            // Drawer pulse
            baos.write(buildDrawerAndCutFooter());
        } catch (Exception ignored) {}
        sendBytesToUsbDevice(device, baos.toByteArray());
    }

    private void sendBytesToUsbDevice(UsbDevice device, byte[] bytes) {
        UsbManager usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
        if (usbManager == null || device == null || bytes == null || bytes.length == 0) return;

        UsbDeviceConnection connection = null;
        UsbInterface claimedInterface = null;
        try {
            connection = usbManager.openDevice(device);
            if (connection == null) {
                runOnUiThread(() -> Toast.makeText(this, "USB connection open nahi ho paaya", Toast.LENGTH_SHORT).show());
                return;
            }

            for (int i = 0; i < device.getInterfaceCount(); i++) {
                UsbInterface iface = device.getInterface(i);
                for (int e = 0; e < iface.getEndpointCount(); e++) {
                    UsbEndpoint ep = iface.getEndpoint(e);
                    if (ep.getDirection() == UsbConstants.USB_DIR_OUT &&
                            (ep.getType() == UsbConstants.USB_ENDPOINT_XFER_BULK || ep.getType() == UsbConstants.USB_ENDPOINT_XFER_INT)) {
                        if (connection.claimInterface(iface, true)) {
                            claimedInterface = iface;
                            int offset = 0;
                            while (offset < bytes.length) {
                                int len = Math.min(512, bytes.length - offset);
                                int sent = connection.bulkTransfer(ep, bytes, offset, len, 3000);
                                if (sent < 0) break;
                                offset += len;
                            }
                            runOnUiThread(() -> Toast.makeText(this, "Printed via USB OTG", Toast.LENGTH_SHORT).show());
                            return;
                        }
                    }
                }
            }
            runOnUiThread(() -> Toast.makeText(this, "USB printer OUT endpoint nahi mila", Toast.LENGTH_SHORT).show());
        } catch (Exception e) {
            runOnUiThread(() -> Toast.makeText(this, "USB print error: " + e.getMessage(), Toast.LENGTH_SHORT).show());
        } finally {
            if (claimedInterface != null && connection != null) {
                try { connection.releaseInterface(claimedInterface); } catch (Exception ignored) {}
            }
            if (connection != null) {
                try { connection.close(); } catch (Exception ignored) {}
            }
        }
    }

    private void triggerUsbPrint(String text, byte[] rawBytes) {
        try {
            UsbManager usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
            UsbDevice device = findPrinterUsbDevice(usbManager);
            if (device == null) {
                runOnUiThread(() -> Toast.makeText(this, "USB / OTG printer connected nahi hai", Toast.LENGTH_SHORT).show());
                return;
            }
            if (usbManager.hasPermission(device)) {
                if (rawBytes != null) sendBytesToUsbDevice(device, rawBytes);
                else sendRawToUsbDevice(device, text);
            } else {
                pendingPrintText = text;
                pendingPrintBytes = rawBytes;
                Intent usbIntent = new Intent(ACTION_USB_PERMISSION);
                usbIntent.setPackage(getPackageName());
                int flags = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) ? PendingIntent.FLAG_MUTABLE : 0;
                PendingIntent pi = PendingIntent.getBroadcast(this, 0, usbIntent, flags);
                usbManager.requestPermission(device, pi);
            }
        } catch (Exception e) {
            runOnUiThread(() -> Toast.makeText(this, "USB trigger error: " + e.getMessage(), Toast.LENGTH_SHORT).show());
        }
    }

    public class AndroidPOSBridge {
        @JavascriptInterface
        public void printUsbRaw(final String text) {
            runOnUiThread(() -> {
                try {
                    triggerUsbPrint(text, null);
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "Print error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                }
            });
        }

        @JavascriptInterface
        public void printEscPosBase64(final String base64Data) {
            runOnUiThread(() -> {
                try {
                    byte[] decoded = Base64.decode(base64Data, Base64.DEFAULT);
                    triggerUsbPrint(null, decoded);
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "Base64 print error", Toast.LENGTH_SHORT).show();
                }
            });
        }

        @JavascriptInterface
        public void printPage() {
            runOnUiThread(() -> {
                try {
                    PrintManager printManager = (PrintManager) getSystemService(Context.PRINT_SERVICE);
                    if (printManager != null && webView != null) {
                        PrintDocumentAdapter adapter = webView.createPrintDocumentAdapter("BBF_POS_Receipt");
                        printManager.print("BBF_POS_Receipt", adapter, new PrintAttributes.Builder().build());
                    }
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "Print error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                }
            });
        }

        @JavascriptInterface
        public void requestPermissions(String requested) {
            runOnUiThread(() -> {
                try {
                    List<String> needed = new ArrayList<>();
                    if (ContextCompat.checkSelfPermission(MainActivity.this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                        needed.add(Manifest.permission.CAMERA);
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        if (ContextCompat.checkSelfPermission(MainActivity.this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                            needed.add(Manifest.permission.BLUETOOTH_CONNECT);
                        }
                        if (ContextCompat.checkSelfPermission(MainActivity.this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                            needed.add(Manifest.permission.BLUETOOTH_SCAN);
                        }
                    }
                    if (!needed.isEmpty()) {
                        ActivityCompat.requestPermissions(MainActivity.this, needed.toArray(new String[0]), 101);
                    }
                } catch (Exception ignored) {}
            });
        }
    }

    @Override
    protected void onDestroy() {
        if (usbReceiverRegistered) {
            try {
                unregisterReceiver(usbPermissionReceiver);
            } catch (Exception ignored) {}
            usbReceiverRegistered = false;
        }
        super.onDestroy();
    }
}
