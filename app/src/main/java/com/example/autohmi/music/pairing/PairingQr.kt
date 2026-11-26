package com.example.autohmi.music.pairing

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.net.URI

/** Encodes only the public relay handle. Rejects authorization, query and credential URLs. */
object PairingQr {
    fun encode(url: String): BitMatrix {
        val uri = URI(url)
        val origin = PairingConfiguration("${uri.scheme}://${uri.rawAuthority}").origin
        require(origin != null && url == "$origin${uri.rawPath}" &&
            uri.rawPath.matches(Regex("/pair/[A-Za-z0-9_-]{43}")))
        return QRCodeWriter().encode(url, BarcodeFormat.QR_CODE, 512, 512,
            mapOf(EncodeHintType.MARGIN to 4, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M))
    }
}
