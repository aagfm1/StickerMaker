package com.stickermaker.app.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.stickermaker.app.data.PackRepository
import com.stickermaker.app.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private val adapter = PackAdapter { pack -> startActivity(PackActivity.intent(this, pack.id)) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setupEdgeToEdge(b.root)
        setContentView(b.root)
        setSupportActionBar(b.toolbar)

        b.list.layoutManager = LinearLayoutManager(this)
        b.list.adapter = adapter

        b.fab.setOnClickListener {
            PackDialog.show(this) { name, publisher, animated ->
                lifecycleScope.launch {
                    val pack = withContext(Dispatchers.IO) {
                        PackRepository.create(applicationContext, name, publisher, animated)
                    }
                    startActivity(PackActivity.intent(this@MainActivity, pack.id))
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch {
            val packs = withContext(Dispatchers.IO) { PackRepository.all(applicationContext) }
            adapter.submit(packs)
            b.empty.isVisible = packs.isEmpty()
        }
    }
}
