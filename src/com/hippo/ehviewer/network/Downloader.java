package com.hippo.ehviewer.network;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.List;
import java.util.Map;

import android.util.Log;

import com.hippo.ehviewer.DownloadInfo;
import com.hippo.ehviewer.util.Cache;
import com.hippo.ehviewer.util.Util;

public class Downloader implements Runnable {
    private static final String TAG = "Downloader";
    
    public static final int HTTP_REQUESTED_RANGE_NOT_SATISFIABLE = 416;
    public static final int HTTP_TEMP_REDIRECT = 307;
    public static final int COMPLETED = 0;
    public static final int STOP = -1;
    public static final int NO_SDCARD = -2;
    public static final int STATUS_HTTP_DATA_ERROR = -3;
    public static final int STATUS_TOO_MANY_REDIRECTS = -4;
    public static final int STATUS_FILE_ERROR = -5;
    
    private static final int BUFFER_SIZE = 4096;
    
    private static final int MAX_REDIRECTS = 5;
    private static final int MAX_RETRY = 5;
    private static final int DEFAULT_TIMEOUT = 5 * 1000;
    
    private String mPath;
    private String mFileName;
    private URL mUrl;
    private Controlor mContorlor;
    
    private int mTotalSize;
    private int mDownloadSize = 0;
    private int mRedirectionCount = 0;
    
    private int status;
    private OnDownloadListener mListener;
    
    public interface OnDownloadListener {
        public void onDownloadStart(int totalSize);
        public void onDownloadStatusUpdate(int downloadSize, int totalSize);
        public void onDownloadOver(boolean ok, int eMesgId);
    }
    
    public class Controlor {
        private boolean mStop = false;
        
        public synchronized void stop() {
            mStop = true; 
        }
        
        public synchronized void reset() {
            mStop = false; 
        }
        
        public synchronized boolean isStop() {
            return mStop; 
        }
    }
    
    public Controlor resetData(String path, String fileName, String urlStr) throws MalformedURLException {
        mPath = path;
        mFileName = fileName;
        mUrl = new URL(urlStr);
        
        mDownloadSize = 0;
        mRedirectionCount = 0;
        status = COMPLETED;
        
        mContorlor.reset();
        return mContorlor;
    }
    
    public void setOnDownloadListener(OnDownloadListener listener) {
        mListener = listener;
    }
    
    public Downloader() {
        mContorlor = new Controlor();
    }
    
    @Override
    public void run() {
        boolean ok = false;
        int retryTimes = 0;
        if (Cache.hasSdCard())
            while(true) {
                try {
                    if (mContorlor.isStop())
                        throw new StopRequestException(STOP, "Download is stopped");
                    mDownloadSize = 0;
                    mRedirectionCount = 0;
                    status = COMPLETED;
                    executeDownload();
                    ok = true;
                    break;
                } catch (StopRequestException e) {
                    e.printStackTrace();
                    status = e.getFinalStatus();
                    if (status == STOP || retryTimes > MAX_RETRY)
                        break;
                    retryTimes++;
                }
            }
        else
            status = NO_SDCARD;
        
        if (mListener != null)
            mListener.onDownloadOver(ok, status);
    }
    
    public int getStatus() {
        return status;
    }
    
    private void executeDownload() throws StopRequestException {
        while (mRedirectionCount++ < MAX_REDIRECTS) {
            HttpURLConnection conn = null;
            try {
                Log.d(TAG, "Get file " + mUrl.toString());
                conn = (HttpURLConnection)mUrl.openConnection();
                conn.setInstanceFollowRedirects(false);
                conn.addRequestProperty("Range", "bytes=0-"); // TODO
                conn.setConnectTimeout(DEFAULT_TIMEOUT);
                conn.setReadTimeout(DEFAULT_TIMEOUT);
                conn.connect();
                final int responseCode = conn.getResponseCode();
                switch (responseCode) {
                    case HttpURLConnection.HTTP_OK:
                    case HttpURLConnection.HTTP_PARTIAL:
                        processResponseHeaders(conn);
                        if (mListener != null)
                            mListener.onDownloadStart(mTotalSize);
                        transferData(conn);
                        return;

                    case HttpURLConnection.HTTP_MOVED_PERM:
                    case HttpURLConnection.HTTP_MOVED_TEMP:
                    case HttpURLConnection.HTTP_SEE_OTHER:
                    case HTTP_TEMP_REDIRECT:
                        final String location = conn.getHeaderField("Location");
                        mUrl = new URL(mUrl, location);
                        continue;

                    default:
                        StopRequestException.throwUnhandledHttpError(
                                responseCode, conn.getResponseMessage());
                }
            } catch (IOException e) {
                // Trouble with low-level sockets
                throw new StopRequestException(STATUS_HTTP_DATA_ERROR, e);

            } finally {
                if (conn != null) conn.disconnect();
            }
        }

        throw new StopRequestException(STATUS_TOO_MANY_REDIRECTS, "Too many redirects");
    }
    
    /**
     * Get Response Headers Info
     * 
     * @param conn
     */
    private void processResponseHeaders(HttpURLConnection conn) {
        mTotalSize = conn.getContentLength();
        String range;
        int index;
        if (mTotalSize == -1
                && (range = conn.getHeaderField("Content-Range")) != null
                && (index = range.lastIndexOf('/')) != -1) {
            try {
                mTotalSize = Integer.parseInt(range.substring(index + 1));
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }
    
    /**
     * Transfer data from the given connection to the destination file.
     */
    private void transferData(HttpURLConnection conn) throws StopRequestException {
        InputStream in = null;
        OutputStream out = null;
        File file = null;
        
        if (mContorlor.isStop())
            throw new StopRequestException(STOP,
                    "Download is stopped");
        
        try {
            try {
                in = conn.getInputStream();
            } catch (IOException e) {
                throw new StopRequestException(STATUS_HTTP_DATA_ERROR, e);
            }

            try {
                file = new File(mPath, mFileName);
                out = new FileOutputStream(file);
            } catch (IOException e) {
                throw new StopRequestException(STATUS_FILE_ERROR, e);
            }

            // Start streaming data, periodically watch for pause/cancel
            // commands and checking disk space as needed.
            transferData(in, out);
        } finally {
            Util.closeStreamQuietly(in);
            try {
                if (out != null) out.flush();
            } catch (IOException e) {
            } finally {
                Util.closeStreamQuietly(out);
            }
        }
    }
    
    /**
     * Transfer as much data as possible from the HTTP response to the
     * destination file.
     */
    private void transferData(InputStream in, OutputStream out)
            throws StopRequestException {
        final byte data[] = new byte[BUFFER_SIZE];
        for (;;) {
            int bytesRead = readFromResponse(data, in);
            if (bytesRead == -1) { // success, end of stream already reached
                return;
            }
            
            writeDataToDestination(data, bytesRead, out);
            mDownloadSize += bytesRead;
            if (mListener != null)
                mListener.onDownloadStatusUpdate(mDownloadSize, mTotalSize);

            if (mContorlor.isStop())
                throw new StopRequestException(STOP,
                        "Download is stopped");
        }
    }
    
    /**
     * Read some data from the HTTP response stream, handling I/O errors.
     * @param data buffer to use to read data
     * @param entityStream stream for reading the HTTP response entity
     * @return the number of bytes actually read or -1 if the end of the stream has been reached
     */
    private int readFromResponse(byte[] data, InputStream entityStream)
            throws StopRequestException {
        try {
            return entityStream.read(data);
        } catch (IOException ex) {
            // TODO: handle stream errors the same as other retries
            throw new StopRequestException(STATUS_HTTP_DATA_ERROR,
                    "Failed reading response: " + ex, ex);
        }
    }
    
    /**
     * Write a data buffer to the destination file.
     * @param data buffer containing the data to write
     * @param bytesRead how many bytes to write from the buffer
     */
    private void writeDataToDestination(byte[] data, int bytesRead, OutputStream out)
            throws StopRequestException {
        try {
            out.write(data, 0, bytesRead);
            return;
        } catch (IOException ex) {
            // TODO: better differentiate between DRM and disk failures
            // TODO: check disk is full
            throw new StopRequestException(STATUS_FILE_ERROR,
                    "Failed to write data: " + ex);
        }
    }
}