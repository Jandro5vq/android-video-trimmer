package com.gowtham.library.utils;

import android.content.Context;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.media.MediaMuxer;
import android.net.Uri;
import android.os.Build;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Fallback video trimmer using MediaMuxer and MediaExtractor
 * Used when Media3 Transformer is not available or fails
 */
public class MediaMuxerTrimmer {
    
    private static final String TAG = "MediaMuxerTrimmer";
    private Context context;
    private volatile boolean isCancelled = false;
    
    public interface MuxerCallback {
        void onSuccess(String outputPath);
        void onProgress(int progress);
        void onError(String error);
        void onCancelled();
    }
    
    public MediaMuxerTrimmer(Context context) {
        this.context = context;
    }
    
    /**
     * Trim video using MediaMuxer and MediaExtractor
     */
    public void trimVideo(String inputPath, String outputPath, long startTimeUs, long endTimeUs, 
                         MuxerCallback callback) {
        
        new Thread(() -> {
            MediaExtractor extractor = null;
            MediaMuxer muxer = null;
            
            try {
                isCancelled = false;
                
                extractor = new MediaExtractor();
                extractor.setDataSource(inputPath);
                
                muxer = new MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
                
                int trackCount = extractor.getTrackCount();
                int videoTrackIndex = -1;
                int audioTrackIndex = -1;
                int muxerVideoTrackIndex = -1;
                int muxerAudioTrackIndex = -1;
                
                // Find video and audio tracks
                for (int i = 0; i < trackCount; i++) {
                    MediaFormat format = extractor.getTrackFormat(i);
                    String mime = format.getString(MediaFormat.KEY_MIME);
                    
                    if (mime != null) {
                        if (mime.startsWith("video/") && videoTrackIndex == -1) {
                            videoTrackIndex = i;
                            muxerVideoTrackIndex = muxer.addTrack(format);
                        } else if (mime.startsWith("audio/") && audioTrackIndex == -1) {
                            audioTrackIndex = i;
                            muxerAudioTrackIndex = muxer.addTrack(format);
                        }
                    }
                }
                
                if (videoTrackIndex == -1) {
                    callback.onError("No video track found in input file");
                    return;
                }
                
                muxer.start();
                
                // Process video track
                if (videoTrackIndex != -1) {
                    trimTrack(extractor, muxer, videoTrackIndex, muxerVideoTrackIndex, 
                             startTimeUs, endTimeUs, callback, true);
                }
                
                // Process audio track if available
                if (audioTrackIndex != -1 && !isCancelled) {
                    trimTrack(extractor, muxer, audioTrackIndex, muxerAudioTrackIndex, 
                             startTimeUs, endTimeUs, callback, false);
                }
                
                if (!isCancelled) {
                    callback.onSuccess(outputPath);
                }
                
            } catch (Exception e) {
                LogMessage.e("Error in MediaMuxer trimming: " + e.getMessage());
                if (!isCancelled) {
                    callback.onError("Trimming failed: " + e.getMessage());
                }
            } finally {
                try {
                    if (muxer != null) {
                        muxer.stop();
                        muxer.release();
                    }
                    if (extractor != null) {
                        extractor.release();
                    }
                } catch (Exception e) {
                    LogMessage.e("Error releasing resources: " + e.getMessage());
                }
            }
            
        }).start();
    }
    
    private void trimTrack(MediaExtractor extractor, MediaMuxer muxer, int sourceTrackIndex, 
                          int muxerTrackIndex, long startTimeUs, long endTimeUs, 
                          MuxerCallback callback, boolean isVideo) throws IOException {
        
        extractor.selectTrack(sourceTrackIndex);
        extractor.seekTo(startTimeUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
        
        ByteBuffer buffer = ByteBuffer.allocate(1024 * 1024); // 1MB buffer
        MediaExtractor.SampleInfo info = new MediaExtractor.SampleInfo();
        
        long durationUs = endTimeUs - startTimeUs;
        long processedUs = 0;
        
        while (!isCancelled) {
            int sampleSize = extractor.readSampleData(buffer, 0);
            
            if (sampleSize < 0) {
                break; // End of track
            }
            
            long sampleTime = extractor.getSampleTime();
            
            if (sampleTime > endTimeUs) {
                break; // Reached end time
            }
            
            if (sampleTime >= startTimeUs) {
                info.presentationTimeUs = sampleTime - startTimeUs;
                info.size = sampleSize;
                info.flags = extractor.getSampleFlags();
                info.offset = 0;
                
                muxer.writeSampleData(muxerTrackIndex, buffer, info);
                
                if (isVideo) {
                    processedUs = sampleTime - startTimeUs;
                    int progress = (int) ((processedUs * 100) / durationUs);
                    callback.onProgress(Math.min(100, Math.max(0, progress)));
                }
            }
            
            extractor.advance();
        }
    }
    
    public void cancel() {
        isCancelled = true;
    }
    
    /**
     * Check if MediaMuxer approach is supported on this device
     */
    public static boolean isSupported() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2;
    }
}