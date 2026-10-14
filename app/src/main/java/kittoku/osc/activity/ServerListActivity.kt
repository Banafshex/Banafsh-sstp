package kittoku.osc.activity

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.URL

class ServerListActivity : AppCompatActivity() {

    private lateinit var progressBar: ProgressBar
    private lateinit var containerServers: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // این فایل XML که ساختیم رو به این اکتیویتی وصل می‌کنه
        setContentView(R.layout.activity_server_list)

        title = "Get Free Config"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        progressBar = findViewById(R.id.progressBar)
        containerServers = findViewById(R.id.containerServers)

        // دانلود لیست سرورها به محض باز شدن صفحه
        fetchAndDisplayServers()
    }

    private fun fetchAndDisplayServers() {
        progressBar.visibility = View.VISIBLE
        GlobalScope.launch(Dispatchers.IO) {
            try {
                val url = URL("https://raw.githubusercontent.com/Banafshex/Banafsh-sstp/main/servers.json")
                val jsonString = url.readText()
                val jsonArray = JSONArray(jsonString)

                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    for (i in 0 until jsonArray.length()) {
                        val obj = jsonArray.getJSONObject(i)
                        val hostName = obj.getString("host_name")
                        val country = obj.getString("country_name")
                        val speed = obj.getDouble("speed_mbps")
                        val ping = obj.getInt("ping")

                        addServerCard(hostName, "$country | Speed: $speed Mbps | Ping: ${ping}ms")
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    Toast.makeText(this@ServerListActivity, "خطا در دریافت سرورها", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun addServerCard(hostName: String, details: String) {
        val cardLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 16, 16, 16)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 0, 16)
            }
        }

        val tvTitle = TextView(this).apply {
            text = hostName
            textSize = 16f
            textStyle = android.graphics.Typeface.BOLD
        }

        val tvDetails = TextView(this).apply {
            text = details
            textSize = 12f
        }

        val btnUse = Button(this).apply {
            text = "Use Now"
            setOnClickListener {
                // اینجا هاست انتخاب شده رو برمی‌گردونیم صفحه اول
                val resultIntent = intent.putExtra("SELECTED_HOST", hostName)
                setResult(RESULT_OK, resultIntent)
                finish() // بستن این صفحه و برگشت به صفحه اول
            }
        }

        cardLayout.addView(tvTitle)
        cardLayout.addView(tvDetails)
        cardLayout.addView(btnUse)
        containerServers.addView(cardLayout)
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
