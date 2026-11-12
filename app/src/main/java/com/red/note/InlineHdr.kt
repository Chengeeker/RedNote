package com.red.note

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.DataInputStream
import java.io.EOFException
import java.io.InputStream

@Composable
internal fun InlineHdrBadge(modifier: Modifier = Modifier, description: String = "HDR 图片", compact: Boolean = false) {
    Text("HDR", modifier.semantics { contentDescription = description }
        .background(Color.Black.copy(alpha = .4f), RoundedCornerShape(4.dp))
        .padding(horizontal = if (compact) 3.dp else 6.dp, vertical = if (compact) 1.dp else 2.dp),
        color = Color.White, fontSize = if (compact) 8.sp else 10.sp, lineHeight = if (compact) 9.sp else 12.sp,
        fontWeight = FontWeight.Normal)
}

internal const val INLINE_HDR_HEADER_BYTES = 64 * 1024

// Stop at the first complete gain-map marker or the start of compressed image data.
internal fun hasGainMapHeader(input: InputStream): Boolean {
    val data = DataInputStream(input)
    try {
        if (data.readUnsignedShort() != 0xffd8) return false
        var offset = 2
        while (offset + 4 <= INLINE_HDR_HEADER_BYTES) {
            if (data.readUnsignedByte() != 0xff) return false
            val marker = data.readUnsignedByte()
            if (marker == 0xda || marker == 0xd9) return false
            val length = data.readUnsignedShort()
            val payload = length - 2
            if (length < 2 || offset + 2 + length > INLINE_HDR_HEADER_BYTES) return false
            if (marker == 0xe1 || marker == 0xe2) {
                val bytes = ByteArray(payload)
                data.readFully(bytes)
                val metadata = bytes.toString(Charsets.ISO_8859_1)
                if (
                    marker == 0xe2 && metadata.startsWith("urn:iso:std:iso:ts:21496:-1\u0000") ||
                        marker == 0xe1 &&
                            metadata.startsWith("http://ns.adobe.com/xap/1.0/\u0000") &&
                            metadata.contains("http://ns.adobe.com/hdr-gain-map/1.0/")
                )
                    return true
            } else if (data.skipBytes(payload) != payload) return false
            offset += 2 + length
        }
    } catch (_: EOFException) {
        return false
    }
    return false
}
