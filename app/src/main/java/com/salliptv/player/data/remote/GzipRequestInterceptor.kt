package com.salliptv.player.data.remote

import okhttp3.Interceptor
import okhttp3.RequestBody
import okhttp3.Response
import okio.GzipSink
import okio.buffer

/**
 * Interceptor that automatically gzip compresses request bodies
 */
class GzipRequestInterceptor : Interceptor {
    
    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()
        
        // Skip if no body, already compressed, or multipart (breaks boundaries)
        if (originalRequest.body == null ||
            originalRequest.header("Content-Encoding") != null ||
            originalRequest.body?.contentType()?.type == "multipart") {
            return chain.proceed(originalRequest)
        }
        
        val compressedRequest = originalRequest.newBuilder()
            .header("Content-Encoding", "gzip")
            .method(originalRequest.method, gzip(originalRequest.body!!))
            .build()
        
        return chain.proceed(compressedRequest)
    }
    
    private fun gzip(body: RequestBody): RequestBody {
        return object : RequestBody() {
            override fun contentType() = body.contentType()
            
            override fun contentLength(): Long = -1 // Unknown due to compression
            
            override fun writeTo(sink: okio.BufferedSink) {
                val gzipSink = GzipSink(sink).buffer()
                body.writeTo(gzipSink)
                gzipSink.close()
            }
        }
    }
}
