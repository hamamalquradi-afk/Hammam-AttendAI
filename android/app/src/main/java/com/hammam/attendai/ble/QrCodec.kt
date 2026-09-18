package com.hammam.attendai.ble

import android.graphics.Bitmap
import com.google.zxing.*
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeWriter

object QrCodec {
    fun encode(text:String,size:Int=640):Bitmap{
        val matrix=QRCodeWriter().encode(text,BarcodeFormat.QR_CODE,size,size)
        val pixels=IntArray(size*size)
        for(y in 0 until size)for(x in 0 until size)pixels[y*size+x]=if(matrix[x,y])android.graphics.Color.BLACK else android.graphics.Color.WHITE
        return Bitmap.createBitmap(size,size,Bitmap.Config.ARGB_8888).apply{setPixels(pixels,0,size,0,0,size,size)}
    }
    fun decode(bitmap:Bitmap):String?=runCatching{
        val w=bitmap.width;val h=bitmap.height;val pixels=IntArray(w*h);bitmap.getPixels(pixels,0,w,0,0,w,h)
        val source=RGBLuminanceSource(w,h,pixels);MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(source))).text
    }.getOrNull()
}
