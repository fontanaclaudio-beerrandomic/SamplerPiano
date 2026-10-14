package com.example.samplerpiano

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.*

class MainActivity : Activity() {
    private lateinit var engine: Engine
    private lateinit var status: TextView
    private lateinit var recBtn: Button
    private var recording = false

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        engine = Engine()
        val d = resources.displayMetrics.density
        val pad = (6 * d).toInt()

        recBtn = Button(this).apply { text = "● Registra"; setOnClickListener { onRecord() } }
        val loadBtn = Button(this).apply {
            text = "Carica file"
            setOnClickListener {
                startActivityForResult(Intent(Intent.ACTION_GET_CONTENT).setType("audio/*")
                    .addCategory(Intent.CATEGORY_OPENABLE), 2)
            }
        }
        val names = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
        val labels = (36..96).map { names[it % 12] + (it / 12 - 1) }
        val spinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, labels)
            setSelection(60 - 36)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) { engine.root = 36 + pos }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
        }
        val loop = CheckBox(this).apply {
            text = "Sustain (loop)"
            setOnCheckedChangeListener { _, c -> engine.loop = c }
        }
        status = TextView(this).apply { text = "Registra o carica un suono per iniziare." }

        val row1 = LinearLayout(this).apply {
            addView(recBtn, LinearLayout.LayoutParams(0, -2, 1f))
            addView(loadBtn, LinearLayout.LayoutParams(0, -2, 1f))
        }
        val row2 = LinearLayout(this).apply {
            gravity = android.view.Gravity.CENTER_VERTICAL
            addView(TextView(context).apply { text = "Nota base: " })
            addView(spinner)
            addView(loop)
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(0xFFEEEEEE.toInt())
            addView(row1); addView(row2); addView(status)
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            fitsSystemWindows = true
            addView(bar)
            addView(KeyboardView(this@MainActivity, engine), LinearLayout.LayoutParams(-1, 0, 1f))
        }
        setContentView(root)
    }

    private fun onRecord() {
        if (!recording) {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1); return
            }
            engine.startRecording()
            recording = true
            recBtn.text = "■ Stop"
            status.text = "Registrazione in corso…"
        } else {
            val sec = engine.stopRecording()
            recording = false
            recBtn.text = "● Registra"
            status.text = if (sec > 0) "Suono pronto (%.1f s). Imposta la nota base e suona.".format(sec)
            else "Registrazione vuota, riprova."
        }
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        if (res.isNotEmpty() && res[0] == PackageManager.PERMISSION_GRANTED) onRecord()
        else status.text = "Permesso microfono negato (Impostazioni > App > Sampler Piano)."
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        val uri = data?.data
        if (req == 2 && res == RESULT_OK && uri != null) {
            status.text = "Carico il file…"
            Thread {
                val msg = try { "Suono pronto (%.1f s). Imposta la nota base e suona.".format(engine.load(this, uri)) }
                catch (e: Exception) { "Impossibile leggere il file: ${e.message}" }
                runOnUiThread { status.text = msg }
            }.start()
        }
    }

    override fun onDestroy() { engine.release(); super.onDestroy() }
}
