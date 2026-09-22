package com.example.taitoulv

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.example.taitoulv.databinding.ActivityRecordsBinding
import com.example.taitoulv.databinding.ItemCsvRecordBinding
import com.google.android.material.button.MaterialButton
import java.io.File

/**
 * 数据记录管理：列出 CSV、看曲线、看原始内容、分享、删除。
 * 记录文件在 App 的外部私有目录，不需要存储权限，也可以直接用 adb pull 拉走。
 */
class RecordsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRecordsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRecordsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Android 15+ 强制 edge-to-edge，系统栏会浮在内容上，这里把状态栏/导航栏的高度补成内边距
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val basePadding = intArrayOf(
            binding.root.paddingLeft, binding.root.paddingTop,
            binding.root.paddingRight, binding.root.paddingBottom
        )
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.setPadding(
                basePadding[0] + bars.left,
                basePadding[1] + bars.top,
                basePadding[2] + bars.right,
                basePadding[3] + bars.bottom
            )
            insets
        }
        ViewCompat.requestApplyInsets(binding.root)

        binding.pathText.text = getString(R.string.records_path_fmt, CsvRepository.dataDir(this).absolutePath)
        binding.refreshButton.setOnClickListener { refresh() }
        binding.deleteAllButton.setOnClickListener { confirmDeleteAll() }
        binding.recordsHint.text = getString(R.string.records_hint)

        refresh()
    }

    private fun refresh() {
        val files = CsvRepository.listFiles(this)
        binding.fileList.removeAllViews()
        val empty = files.isEmpty()
        binding.emptyText.visibility = if (empty) View.VISIBLE else View.GONE
        binding.fileScroll.visibility = if (empty) View.GONE else View.VISIBLE

        files.forEach { info ->
            val row = ItemCsvRecordBinding.inflate(layoutInflater, binding.fileList, false)
            row.fileName.text = info.file.name
            row.fileMeta.text = getString(
                R.string.record_meta_fmt,
                CsvRepository.sizeText(info.file),
                CsvRepository.rowCount(info.file),
                CsvRepository.timeText(info.file)
            )
            row.chartButton.setOnClickListener { showChart(info.file) }
            row.viewButton.setOnClickListener { showContent(info.file) }
            row.shareButton.setOnClickListener { share(info.file) }
            row.deleteButton.setOnClickListener { confirmDelete(info.file) }
            binding.fileList.addView(row.root)
        }

        if (files.isEmpty()) {
            binding.chartView.setData(emptyList(), emptyList())
            binding.summaryText.text = getString(R.string.records_empty)
        }
    }

    // ---------------------------------------------------------------- 图表

    private fun showChart(detailFile: File) {
        val samples = CsvRepository.readDetail(detailFile)
        val eventFile = CsvRepository.eventFileFor(detailFile)
        val events = if (eventFile != null && eventFile.exists()) CsvRepository.readEvents(eventFile) else emptyList()

        binding.chartView.setData(samples, events)
        binding.chartTitle.text = detailFile.name

        val summary = CsvRepository.summaryOf(samples, events)
        binding.summaryText.text = if (summary == null) {
            getString(R.string.records_empty)
        } else {
            getString(
                R.string.records_summary_fmt,
                summary.durationSec,
                summary.avgRate,
                summary.minRate,
                summary.maxRate,
                summary.avgVisible,
                summary.headDownEvents,
                summary.lyingEvents
            )
        }
    }

    // ---------------------------------------------------------------- 查看 / 分享 / 删除

    private fun showContent(file: File) {
        val text = runCatching { file.readLines(Charsets.UTF_8).take(MAX_PREVIEW_ROWS).joinToString("\n") }
            .getOrDefault("")
        val textView = TextView(this).apply {
            this.text = text
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setPadding(28, 28, 28, 28)
            setTextIsSelectable(true)
        }
        val scroll = ScrollView(this).apply { addView(textView) }
        AlertDialog.Builder(this)
            .setTitle(file.name)
            .setView(scroll)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun share(file: File) {
        runCatching {
            val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/csv"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, file.name)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, getString(R.string.records_share)))
        }.onFailure {
            Toast.makeText(this, it.message ?: it.javaClass.simpleName, Toast.LENGTH_SHORT).show()
        }
    }

    private fun confirmDelete(file: File) {
        AlertDialog.Builder(this)
            .setTitle(R.string.records_delete_confirm)
            .setMessage(file.name)
            .setPositiveButton(R.string.records_delete) { _, _ ->
                CsvRepository.delete(file)
                Toast.makeText(this, R.string.records_deleted, Toast.LENGTH_SHORT).show()
                refresh()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmDeleteAll() {
        val files = CsvRepository.listFiles(this).map { it.file }
        if (files.isEmpty()) return
        AlertDialog.Builder(this)
            .setTitle(R.string.records_delete_all_confirm)
            .setMessage(getString(R.string.records_delete_all_message, files.size))
            .setPositiveButton(R.string.records_delete) { _, _ ->
                files.forEach { CsvRepository.delete(it) }
                Toast.makeText(this, R.string.records_deleted, Toast.LENGTH_SHORT).show()
                refresh()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    companion object {
        private const val MAX_PREVIEW_ROWS = 400
    }
}
