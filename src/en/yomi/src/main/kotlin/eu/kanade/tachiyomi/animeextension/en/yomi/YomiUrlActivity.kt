package eu.kanade.tachiyomi.animeextension.en.yomi

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.util.Log
import eu.kanade.tachiyomi.animesource.AnimeSourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class YomiUrlActivity : Activity() {

    private val sourceManager: AnimeSourceManager by inject()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val path = intent.data?.path ?: return finish()
        
        val animeId = path.substringAfter("/anime/").substringAfter("/watch/").substringBefore("/")
        
        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                data = android.net.Uri.parse("anizen://anime/$animeId")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Log.e("YomiUrlActivity", "Failed to open deep link", e)
        }
        finish()
    }

    private inline fun <reified T> inject(): Lazy<T> = lazy { Injekt.get<T>() }
}
