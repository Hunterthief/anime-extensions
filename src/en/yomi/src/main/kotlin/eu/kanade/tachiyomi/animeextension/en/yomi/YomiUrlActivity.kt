package eu.kanade.tachiyomi.animeextension.en.yomi

import android.app.Activity
import android.content.Intent
import android.os.Bundle

class YomiUrlActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val path = intent.data?.path ?: return finish()
        
        // Extract anime ID from paths like /anime/20665 or /watch/20665/1
        val animeId = path.substringAfter("/anime/").substringAfter("/watch/").substringBefore("/")
        
        val intent = Intent(Intent.ACTION_VIEW).apply {
            data = android.net.Uri.parse("anizen://anime/$animeId")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
        finish()
    }
}
