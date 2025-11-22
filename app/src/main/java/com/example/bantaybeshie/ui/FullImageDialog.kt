package com.example.bantaybeshie.ui

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.widget.ImageView
import com.example.bantaybeshie.R
import java.io.File
import android.graphics.BitmapFactory

class FullImageDialog(
    context: Context,
    private val file: File
) : Dialog(context) {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        setContentView(R.layout.dialog_full_image)

        val img = findViewById<ImageView>(R.id.fullImageView)
        img.setImageBitmap(BitmapFactory.decodeFile(file.absolutePath))
    }
}
