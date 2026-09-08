package com.voxwolf.dictation.onboarding

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.voxwolf.dictation.R
import com.voxwolf.dictation.VoxWolfApp
import com.voxwolf.dictation.asr.ModelProvisioner
import com.voxwolf.dictation.service.CaptureService

/**
 * SPEC §11.2 — Five-step onboarding checklist.
 *
 * 1. RECORD_AUDIO permission
 * 2. SYSTEM_ALERT_WINDOW (draw over other apps)
 * 3. Accessibility Service
 * 4. Battery optimisation exemption (optional)
 * 5. Model ready
 *
 * Each step shows a description, current status, and a grant/enable/open button.
 * Once all required steps (1–3, 5) are satisfied, the CaptureService starts.
 */
class OnboardingActivity : AppCompatActivity() {

    // Step views
    private lateinit var stepViews: List<StepViewHolder>
    private lateinit var launchButton: Button

    // Permission launcher
    private val micPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            refreshSteps()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = ScrollView(this)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }
        root.addView(container)

        // Title
        val title = TextView(this).apply {
            text = getString(R.string.onboarding_title)
            textSize = 24f
            setTextColor(0xFFFFFFFF.toInt())
            val margin = (16 * resources.displayMetrics.density).toInt()
            setPadding(0, 0, 0, margin)
        }
        container.addView(title)

        // Build the 5 steps
        stepViews = listOf(
            createStep(container, getString(R.string.step_record_audio), getString(R.string.step_record_audio_desc), getString(R.string.btn_grant)) {
                micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            },
            createStep(container, getString(R.string.step_overlay), getString(R.string.step_overlay_desc), getString(R.string.btn_open_settings)) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
            },
            createStep(container, getString(R.string.step_accessibility), getString(R.string.step_accessibility_desc), getString(R.string.btn_enable)) {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            },
            createStep(container, getString(R.string.step_battery), getString(R.string.step_battery_desc), getString(R.string.btn_open_settings)) {
                try {
                    @Suppress("BatteryLife")
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } catch (_: Exception) {
                    startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                }
            },
            createStep(container, getString(R.string.step_model), getString(R.string.step_model_desc), null) {
                // Model provisioning runs automatically
            }
        )

        // Samsung-specific battery card
        if (android.os.Build.MANUFACTURER.equals("samsung", ignoreCase = true)) {
            val samsungCard = TextView(this).apply {
                text = getString(R.string.samsung_battery_card)
                textSize = 13f
                setTextColor(0xFFFFC107.toInt())
                val margin = (8 * resources.displayMetrics.density).toInt()
                setPadding(margin, margin, margin, margin)
            }
            container.addView(samsungCard)
        }

        // Launch button
        launchButton = Button(this).apply {
            text = "Start VoxWolf"
            isEnabled = false
            val margin = (16 * resources.displayMetrics.density).toInt()
            setPadding(margin, margin, margin, margin)
            setOnClickListener { launchService() }
        }
        container.addView(launchButton)

        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        refreshSteps()
    }

    private fun refreshSteps() {
        val micGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        val overlayGranted = Settings.canDrawOverlays(this)

        val a11yEnabled = isAccessibilityServiceEnabled()

        val batteryOptimised = isBatteryOptimised()

        val modelReady = isModelReady()

        updateStep(0, micGranted, required = true)
        updateStep(1, overlayGranted, required = true)
        updateStep(2, a11yEnabled, required = true)
        updateStep(3, batteryOptimised, required = false)
        updateStep(4, modelReady, required = true)

        // All required steps satisfied?
        val allRequired = micGranted && overlayGranted && a11yEnabled && modelReady
        launchButton.isEnabled = allRequired

        // Auto-start if returning after granting everything
        if (allRequired && intent.getBooleanExtra("auto_start", false)) {
            launchService()
        }
    }

    private fun updateStep(index: Int, satisfied: Boolean, required: Boolean) {
        val holder = stepViews[index]
        if (satisfied) {
            holder.statusText.text = if (required) getString(R.string.status_granted)
            else getString(R.string.status_enabled)
            holder.statusText.setTextColor(0xFF4CAF50.toInt())
            holder.actionButton?.isEnabled = false
        } else {
            holder.statusText.text = if (required) getString(R.string.status_denied)
            else getString(R.string.status_advisory)
            holder.statusText.setTextColor(
                if (required) 0xFFF44336.toInt() else 0xFFFFC107.toInt()
            )
            holder.actionButton?.isEnabled = true
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val enabledServices = am.getEnabledAccessibilityServiceList(
            AccessibilityServiceInfo.FEEDBACK_ALL_MASK
        )
        val myComponent = ComponentName(
            packageName,
            "com.voxwolf.dictation.service.VoxWolfAccessibilityService"
        )
        return enabledServices.any {
            it.resolveInfo?.serviceInfo?.let { si ->
                ComponentName(si.packageName, si.name) == myComponent
            } ?: false
        }
    }

    private fun isBatteryOptimised(): Boolean {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    private fun isModelReady(): Boolean {
        val provisioner = ModelProvisioner(this)
        return provisioner.modelPath != null
    }

    private fun launchService() {
        // Provision model in background if needed, then start the service
        Thread {
            val provisioner = ModelProvisioner(this)
            val result = provisioner.provision()
            if (result is ModelProvisioner.ProvisionResult.Success) {
                runOnUiThread {
                    val intent = Intent(this, CaptureService::class.java)
                    startForegroundService(intent)
                    // Move to background — user returns to their app
                    moveTaskToBack(true)
                }
            } else {
                runOnUiThread {
                    refreshSteps()
                }
            }
        }.start()
    }

    private fun createStep(
        parent: LinearLayout,
        title: String,
        description: String,
        buttonText: String?,
        action: () -> Unit
    ): StepViewHolder {
        val density = resources.displayMetrics.density
        val stepLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (12 * density).toInt()
            setPadding(pad, pad, pad, pad)
            val margin = (8 * density).toInt()
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.setMargins(0, margin, 0, margin)
            layoutParams = lp
        }

        val titleView = TextView(this).apply {
            text = title
            textSize = 16f
            setTextColor(0xFFFFFFFF.toInt())
        }
        stepLayout.addView(titleView)

        val descView = TextView(this).apply {
            text = description
            textSize = 13f
            setTextColor(0xFFB0BEC5.toInt())
        }
        stepLayout.addView(descView)

        val statusView = TextView(this).apply {
            textSize = 14f
            val pad = (4 * density).toInt()
            setPadding(0, pad, 0, pad)
        }
        stepLayout.addView(statusView)

        val button = if (buttonText != null) {
            Button(this).apply {
                text = buttonText
                setOnClickListener { action() }
            }
        } else null

        button?.let { stepLayout.addView(it) }

        parent.addView(stepLayout)
        return StepViewHolder(titleView, descView, statusView, button)
    }

    private data class StepViewHolder(
        val titleText: TextView,
        val descText: TextView,
        val statusText: TextView,
        val actionButton: Button?
    )
}
