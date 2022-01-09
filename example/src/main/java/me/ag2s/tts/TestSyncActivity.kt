package me.ag2s.tts

import android.os.Bundle
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity

/**
 * # TestSyncActivity
 *
 * @author Libra
 * @date 2022/1/8
 */
class TestSyncActivity :AppCompatActivity(){

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(Button(this).also{
            it.setOnClickListener {

            }
        })
    }
}