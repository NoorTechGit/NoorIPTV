package com.salliptv.player.glide

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Picture
import com.bumptech.glide.Glide
import com.bumptech.glide.Registry
import com.bumptech.glide.annotation.GlideModule
import com.bumptech.glide.load.Options
import com.bumptech.glide.load.ResourceDecoder
import com.bumptech.glide.load.engine.Resource
import com.bumptech.glide.load.engine.bitmap_recycle.BitmapPool
import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.load.resource.bitmap.BitmapResource
import com.bumptech.glide.module.AppGlideModule
import com.caverock.androidsvg.SVG
import java.io.InputStream

/**
 * Glide module that adds SVG decoding support for picons logos.
 * Only decodes SVG when the URL ends with .svg — other formats use default decoders.
 */
@GlideModule
class SvgModule : AppGlideModule() {
    override fun registerComponents(context: Context, glide: Glide, registry: Registry) {
        // Prepend SVG decoder so it runs before default decoders for SVG content
        registry.prepend(InputStream::class.java, Bitmap::class.java, SvgDecoder(glide.bitmapPool))
    }
}

/** Decode SVG InputStream → Bitmap. Returns null for non-SVG so Glide falls through to default decoders. */
class SvgDecoder(private val bitmapPool: BitmapPool) : ResourceDecoder<InputStream, Bitmap> {
    override fun handles(source: InputStream, options: Options): Boolean {
        // Check if the data source is likely SVG by peeking at the content
        source.mark(256)
        try {
            val header = ByteArray(256)
            val read = source.read(header, 0, 256)
            source.reset()
            if (read > 0) {
                val text = String(header, 0, read, Charsets.UTF_8).trimStart()
                return text.startsWith("<?xml") && text.contains("<svg") ||
                       text.startsWith("<svg")
            }
        } catch (_: Exception) {
            try { source.reset() } catch (_: Exception) {}
        }
        return false
    }

    override fun decode(source: InputStream, width: Int, height: Int, options: Options): Resource<Bitmap>? {
        return try {
            val svg = SVG.getFromInputStream(source)
            val w = if (width > 0) width.toFloat() else 256f
            val h = if (height > 0) height.toFloat() else 256f
            svg.documentWidth = w
            svg.documentHeight = h
            val picture: Picture = svg.renderToPicture()
            val bitmap = bitmapPool.get(picture.width, picture.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawPicture(picture)
            BitmapResource(bitmap, bitmapPool)
        } catch (e: Exception) {
            null
        }
    }
}
