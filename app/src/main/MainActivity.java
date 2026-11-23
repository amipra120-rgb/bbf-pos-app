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
    private static final String DEFAULT_UPI_ID = "Vyapar.173291080735@hdfcbank";
    private static final String DEFAULT_PAYEE_NAME = "BIG BOY'S FASHION";
    private static final int LINE_WIDTH = 42; // 80mm thermal printer standard width

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
            baos.write(new byte[]{0x1B, 0x70, 0x00, 0x19, (byte) 0xFA});
            baos.write(new byte[]{0x1B, 0x70, 0x01, 0x19, (byte) 0xFA});
        } catch (IOException ignored) {}
        return baos.toByteArray();
    }

    private byte[] buildEscPosBarcode(String data) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            if (data == null || data.trim().isEmpty()) return new byte[0];
            String clean = data.trim();
            String code128Payload = "{B" + clean;
            byte[] dataBytes = code128Payload.getBytes(StandardCharsets.US_ASCII);

            out.write(new byte[]{0x1B, 0x61, 0x01});
            out.write(new byte[]{0x1D, 0x68, 0x36});
            out.write(new byte[]{0x1D, 0x77, 0x02});
            out.write(new byte[]{0x1D, 0x48, 0x00}); // HRI off since "Bill: BBF-xxxx" is printed cleanly
            out.write(new byte[]{0x1D, 0x6B, 0x49, (byte) dataBytes.length});
            out.write(dataBytes);
            out.write("\n".getBytes(StandardCharsets.UTF_8));
            out.write(new byte[]{0x1B, 0x61, 0x00});
        } catch (Exception ignored) {}
        return out.toByteArray();
    }

    private byte[] buildEscPosQrCode(String qrData, int moduleSize) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            if (qrData == null || qrData.trim().isEmpty()) return new byte[0];
            byte[] dataBytes = qrData.getBytes(StandardCharsets.UTF_8);
            int storeLen = dataBytes.length + 3;
            byte pL = (byte) (storeLen & 0xFF);
            byte pH = (byte) ((storeLen >> 8) & 0xFF);

            out.write(new byte[]{0x1B, 0x61, 0x01});
            out.write(new byte[]{0x1D, 0x28, 0x6B, 0x04, 0x00, 0x31, 0x41, 0x32, 0x00});

            byte size = (byte) Math.max(3, Math.min(10, moduleSize));
            out.write(new byte[]{0x1D, 0x28, 0x6B, 0x03, 0x00, 0x31, 0x43, size});
            out.write(new byte[]{0x1D, 0x28, 0x6B, 0x03, 0x00, 0x31, 0x45, 0x31});
            out.write(new byte[]{0x1D, 0x28, 0x6B, pL, pH, 0x31, 0x50, 0x30});
            out.write(dataBytes);
            out.write(new byte[]{0x1D, 0x28, 0x6B, 0x03, 0x00, 0x31, 0x51, 0x30});
            out.write("\n".getBytes(StandardCharsets.UTF_8));
            out.write(new byte[]{0x1B, 0x61, 0x00});
        } catch (Exception ignored) {}
        return out.toByteArray();
    }

    private String extractField(String text, String labelRegex) {
        if (text == null) return "";
        Matcher m = Pattern.compile("(?im)^\s*" + labelRegex + "\s*[:#\-]?\s*(.+)$").matcher(text);
        if (m.find()) {
            return m.group(1).trim();
        }
        return "";
    }

    private String extractBillNumber(String text) {
        String raw = extractField(text, "Bill");
        if (!raw.isEmpty()) {
            String[] parts = raw.split("\s*\|\s*");
            return parts[0].trim();
        }
        if (text != null) {
            Matcher m = Pattern.compile("(?i)Bill\s*[:#\-]?\s*([A-Za-z0-9\-]+)").matcher(text);
            if (m.find()) return m.group(1).trim();
        }
        return "";
    }

    private String extractMode(String text) {
        if (text == null) return "";
        Matcher inline = Pattern.compile("(?i)Mode\s*[:\-]\s*([A-Za-z0-9 /]+)").matcher(text);
        if (inline.find()) {
            return inline.group(1).trim();
        }
        return "";
    }

    private String extractNumber(String text, String labelRegex) {
        if (text == null) return "";
        Matcher m = Pattern.compile("(?im)(?:" + labelRegex + ")\s*[:]?\s*(?:Rs\.?|INR|₹)?\s*([0-9]+(?:\.[0-9]{1,2})?)").matcher(text);
        String found = "";
        while (m.find()) {
            found = m.group(1).trim();
        }
        return found;
    }

    private String formatAmount(String val) {
        if (val == null || val.trim().isEmpty()) return "0.00";
        try {
            double d = Double.parseDouble(val.trim());
            return String.format(java.util.Locale.US, "%.2f", d);
        } catch (Exception e) {
            return val.trim();
        }
    }

    private String leftRight(String left, String right) {
        String l = (left == null) ? "" : left.trim();
        String r = (right == null) ? "" : right.trim();
        int spaces = LINE_WIDTH - l.length() - r.length();
        if (spaces < 1) spaces = 1;
        StringBuilder sb = new StringBuilder();
        sb.append(l);
        for (int i = 0; i < spaces; i++) sb.append(' ');
        sb.append(r);
        return sb.toString();
    }

    private String repeatChar(char ch, int count) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++) sb.append(ch);
        return sb.toString();
    }

    private List<String[]> parseItems(String text) {
        List<String[]> items = new ArrayList<>();
        if (text == null) return items;
        String[] lines = text.split("\r?\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) continue;
            if (line.matches("(?i).*(?:BBF\s*-\s*BIG\s*BOY|Chhota\s*Baghada|Mob:|GSTIN:|TAX\s*INVOICE|Bill\s*:|Date:|Staff:|Cust:|Subtotal:|GST:|GRAND\s*TOTAL|Loyalty|PAY\s*EXACT|Thank\s*You|Exchange\s*within|upi://pay).*")) {
                continue;
            }
            // Pattern 1: "Item Name ... 1 x Rs.499 = Rs.499.00" or "Item Name   Rs.499.00"
            Matcher mInline = Pattern.compile("^(.+?)\s+(?:([0-9]+\s*x\s*(?:Rs\.?|₹)?\s*[0-9.]+)\s*=\s*)?(?:Rs\.?|INR|₹)\s*([0-9]+(?:\.[0-9]{1,2})?)\s*$").matcher(line);
            if (mInline.find()) {
                String name = mInline.group(1).trim();
                String qtyRate = (mInline.group(2) != null) ? mInline.group(2).trim() : "";
                String total = formatAmount(mInline.group(3));
                items.add(new String[]{name, qtyRate, total});
                continue;
            }
            // Pattern 2: Item name on current line, qty x rate and/or amount on next line
            if (i + 1 < lines.length) {
                String next = lines[i + 1].trim();
                Matcher mQtyAmt = Pattern.compile("^(?:([0-9]+\s*x\s*(?:Rs\.?|₹)?\s*[0-9.]+)\s*(?:=\s*)?)?(?:Rs\.?|INR|₹)\s*([0-9]+(?:\.[0-9]{1,2})?)\s*$").matcher(next);
                if (mQtyAmt.find()) {
                    String qtyRate = (mQtyAmt.group(1) != null) ? mQtyAmt.group(1).trim() : "";
                    String total = formatAmount(mQtyAmt.group(2));
                    items.add(new String[]{line, qtyRate, total});
                    i++;
                }
            }
        }
        return items;
    }

    private String buildUpiPayload(String amount, String billNo) {
        if (amount == null || amount.trim().isEmpty()) return "";
        String cleanBill = (billNo != null) ? billNo.trim().replaceAll("[^A-Za-z0-9]", "") : "";
        String trRef = !cleanBill.isEmpty() ? cleanBill : ("BBF" + (System.currentTimeMillis() % 100000));
        String note = (billNo != null && !billNo.trim().isEmpty()) ? ("Bill " + billNo.trim()) : "BBF POS";
        return "upi://pay?pa=" + Uri.encode(DEFAULT_UPI_ID)
                + "&pn=" + Uri.encode(DEFAULT_PAYEE_NAME)
                + "&tr=" + Uri.encode(trRef)
                + "&am=" + Uri.encode(amount.trim())
                + "&cu=INR";
    }

    private void sendRawToUsbDevice(UsbDevice device, String text) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try {
            // Initialize printer
            baos.write(new byte[]{0x1B, 0x40});

            String billNo = extractBillNumber(text);
            String dateStr = extractField(text, "Date");
            String staffStr = extractField(text, "Staff");
            String custStr = extractField(text, "Cust");
            String modeStr = extractMode(text);
            if (modeStr.isEmpty() && text != null && text.toUpperCase().contains("UPI")) {
                modeStr = "UPI";
            }

            String subtotal = extractNumber(text, "Subtotal");
            String gst = extractNumber(text, "GST");
            String grandTotal = extractNumber(text, "GRAND\s*TOTAL|PAY\s*EXACT|NET\s*AMOUNT|TOTAL");
            String loyalty = extractField(text, "Loyalty\s*Pts\s*Earned|Loyalty");
            List<String[]> items = parseItems(text);

            // 1. Header (Centered, bold store title)
            baos.write(new byte[]{0x1B, 0x61, 0x01}); // Center
            baos.write(new byte[]{0x1B, 0x45, 0x01}); // Bold ON
            baos.write(new byte[]{0x1D, 0x21, 0x10}); // Double height
            baos.write("BBF - BIG BOY'S FASHION\n".getBytes(StandardCharsets.UTF_8));
            baos.write(new byte[]{0x1D, 0x21, 0x00}); // Normal size
            baos.write(new byte[]{0x1B, 0x45, 0x00}); // Bold OFF

            baos.write("Chhota Baghada, Prayagraj, Uttar Pradesh 211002\n".getBytes(StandardCharsets.UTF_8));
            baos.write("Mob: 7380792557\n".getBytes(StandardCharsets.UTF_8));
            baos.write("GSTIN: 09AABCB1234F1Z5\n".getBytes(StandardCharsets.UTF_8));
            baos.write("TAX INVOICE\n".getBytes(StandardCharsets.UTF_8));

            // 2. Top Barcode for current Bill Number
            if (!billNo.isEmpty()) {
                baos.write("\n".getBytes(StandardCharsets.UTF_8));
                baos.write(buildEscPosBarcode(billNo));
            }

            // 3. Bill Meta Info (Left aligned + Mode on right)
            baos.write(new byte[]{0x1B, 0x61, 0x00}); // Left
            if (!billNo.isEmpty() || !modeStr.isEmpty()) {
                String leftBill = !billNo.isEmpty() ? ("Bill: " + billNo) : "";
                String rightMode = !modeStr.isEmpty() ? ("Mode: " + modeStr) : "";
                baos.write((leftRight(leftBill, rightMode) + "\n").getBytes(StandardCharsets.UTF_8));
            }
            if (!dateStr.isEmpty()) {
                baos.write(("Date: " + dateStr + "\n").getBytes(StandardCharsets.UTF_8));
            }
            if (!staffStr.isEmpty()) {
                baos.write(("Staff: " + staffStr + "\n").getBytes(StandardCharsets.UTF_8));
            }
            if (!custStr.isEmpty()) {
                baos.write(("Cust: " + custStr + "\n").getBytes(StandardCharsets.UTF_8));
            }

            // 4. Items Section
            baos.write((repeatChar('-', LINE_WIDTH) + "\n").getBytes(StandardCharsets.UTF_8));
            for (String[] item : items) {
                String name = item[0];
                String qtyRate = item[1];
                String amt = "Rs." + item[2];
                baos.write((name + "\n").getBytes(StandardCharsets.UTF_8));
                if (!qtyRate.isEmpty()) {
                    baos.write((leftRight("  " + qtyRate, amt) + "\n").getBytes(StandardCharsets.UTF_8));
                } else {
                    baos.write((leftRight("", amt) + "\n").getBytes(StandardCharsets.UTF_8));
                }
            }
            baos.write((repeatChar('-', LINE_WIDTH) + "\n").getBytes(StandardCharsets.UTF_8));

            // 5. Totals Section
            if (!subtotal.isEmpty()) {
                baos.write((leftRight("Subtotal:", "Rs." + formatAmount(subtotal)) + "\n").getBytes(StandardCharsets.UTF_8));
            }
            if (!gst.isEmpty()) {
                baos.write((leftRight("GST:", "Rs." + formatAmount(gst)) + "\n").getBytes(StandardCharsets.UTF_8));
            }
            if (!grandTotal.isEmpty()) {
                baos.write(new byte[]{0x1B, 0x45, 0x01}); // Bold ON
                baos.write((leftRight("GRAND TOTAL:", "Rs." + formatAmount(grandTotal)) + "\n").getBytes(StandardCharsets.UTF_8));
                baos.write(new byte[]{0x1B, 0x45, 0x00}); // Bold OFF
            }
            if (!loyalty.isEmpty()) {
                String cleanLoyalty = loyalty.replaceAll("(?i)^Loyalty\s*Pts\s*Earned\s*[:]?\s*", "").trim();
                baos.write((leftRight("Loyalty Pts Earned:", cleanLoyalty) + "\n").getBytes(StandardCharsets.UTF_8));
            }

            // 6. Bottom Amount QR Section
            String fmtTotal = !grandTotal.isEmpty() ? formatAmount(grandTotal) : "";
            String upiPayload = buildUpiPayload(fmtTotal, billNo);
            if (!upiPayload.isEmpty()) {
                baos.write("\n".getBytes(StandardCharsets.UTF_8));
                baos.write(new byte[]{0x1B, 0x61, 0x01}); // Center
                baos.write(new byte[]{0x1B, 0x45, 0x01}); // Bold ON
                baos.write(("PAY EXACT: Rs." + fmtTotal + "\n").getBytes(StandardCharsets.UTF_8));
                baos.write(new byte[]{0x1B, 0x45, 0x00}); // Bold OFF
                baos.write(buildEscPosQrCode(upiPayload, 6));
            }

            // 7. Footer
            baos.write("\n".getBytes(StandardCharsets.UTF_8));
            baos.write(new byte[]{0x1B, 0x61, 0x01}); // Center
            baos.write("Thank You! Visit Again.\n".getBytes(StandardCharsets.UTF_8));
            baos.write("Exchange within 7 days with bill and tag.\n".getBytes(StandardCharsets.UTF_8));
            baos.write(new byte[]{0x1B, 0x61, 0x00});

            baos.write("\n\n\n".getBytes(StandardCharsets.UTF_8));
            // Paper cut
            baos.write(new byte[]{0x1D, 0x56, 0x42, 0x00});
            // Cash drawer pulse
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
