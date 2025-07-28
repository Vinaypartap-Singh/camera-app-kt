package com.vinay.camerafirebase

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.google.firebase.FirebaseApp
import com.google.firebase.storage.FirebaseStorage
import com.vinay.camerafirebase.databinding.ActivityMainBinding
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : ComponentActivity() {

    private lateinit var binding: ActivityMainBinding
    private var imageCapture: ImageCapture? = null
    private lateinit var outputDirectory: File
    private val storage = FirebaseStorage.getInstance()
    private var isPreviewMode = false
    private var currentImageDownloadUrl: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        FirebaseApp.initializeApp(this)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        outputDirectory = getOutputDirectory()

        if (allPermissionsGranted()) {
            startCamera()
        } else {
            requestPermissions.launch(getRequiredPermissions())
        }

        binding.cameraCaptureButton.setOnClickListener {
            takePhoto()
        }

        binding.backToCameraButton.setOnClickListener {
            showCameraPreview()
        }

        binding.viewOnFirebaseButton.setOnClickListener {
            openFirebaseStorage()
        }
    }

    private fun takePhoto() {
        val imageCapture = imageCapture ?: return

        val photoFile = File(
            outputDirectory,
            SimpleDateFormat("yyyy-MM-dd-HH-mm-ss-SSS", Locale.US)
                .format(System.currentTimeMillis()) + ".jpg"
        )

        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

        imageCapture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onError(exc: ImageCaptureException) {
                    Toast.makeText(baseContext, "Capture failed: ${exc.message}", Toast.LENGTH_SHORT).show()
                    Log.e("CameraFirebase", "Image capture failed", exc)
                }

                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    val savedUri = Uri.fromFile(photoFile)
                    Toast.makeText(baseContext, "Photo captured successfully", Toast.LENGTH_SHORT).show()

                    // Show the captured image immediately
                    showCapturedImage(savedUri)

                    // Upload to Firebase
                    uploadToFirebase(savedUri)
                }
            }
        )
    }

    private fun showCapturedImage(imageUri: Uri) {
        isPreviewMode = true

        // Hide camera preview and show captured image
        binding.viewFinder.visibility = View.GONE
        binding.capturedImageView.visibility = View.VISIBLE

        // Load the image into ImageView
        binding.capturedImageView.setImageURI(imageUri)

        // Update button states
        binding.cameraCaptureButton.text = "Capture Another"
        binding.backToCameraButton.visibility = View.VISIBLE
        binding.viewOnFirebaseButton.visibility = View.GONE // Hide until upload is complete

        Log.d("CameraFirebase", "Showing captured image: $imageUri")
    }

    private fun showCameraPreview() {
        isPreviewMode = false

        // Show camera preview and hide captured image
        binding.viewFinder.visibility = View.VISIBLE
        binding.capturedImageView.visibility = View.GONE

        // Clear the ImageView
        binding.capturedImageView.setImageDrawable(null)

        // Update button states
        binding.cameraCaptureButton.text = "Capture"
        binding.backToCameraButton.visibility = View.GONE
        binding.viewOnFirebaseButton.visibility = View.GONE

        // Clear the download URL
        currentImageDownloadUrl = null

        Log.d("CameraFirebase", "Showing camera preview")
    }

    private fun uploadToFirebase(fileUri: Uri) {
        val fileName = fileUri.lastPathSegment ?: UUID.randomUUID().toString() + ".jpg"
        val ref = storage.reference.child("images/$fileName")

        Toast.makeText(this, "Uploading to Firebase...", Toast.LENGTH_SHORT).show()

        ref.putFile(fileUri)
            .addOnSuccessListener { taskSnapshot ->
                // Get download URL
                ref.downloadUrl.addOnSuccessListener { downloadUri ->
                    currentImageDownloadUrl = downloadUri.toString()
                    Toast.makeText(this, "Uploaded successfully to Firebase!", Toast.LENGTH_LONG).show()
                    Log.d("CameraFirebase", "Upload successful: $fileName, URL: $currentImageDownloadUrl")

                    // Show the "View on Firebase" button
                    binding.viewOnFirebaseButton.visibility = View.VISIBLE
                }.addOnFailureListener { exception ->
                    Toast.makeText(this, "Failed to get download URL: ${exception.message}", Toast.LENGTH_LONG).show()
                    Log.e("CameraFirebase", "Failed to get download URL", exception)
                }
            }
            .addOnFailureListener { exception ->
                Toast.makeText(this, "Upload failed: ${exception.message}", Toast.LENGTH_LONG).show()
                Log.e("CameraFirebase", "Upload failed", exception)
            }
    }

    private fun openFirebaseStorage() {
        try {
            currentImageDownloadUrl?.let { imageUrl ->
                // Open the direct image URL
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(imageUrl))
                startActivity(intent)
                Log.d("CameraFirebase", "Opening image URL: $imageUrl")
            } ?: run {
                // Fallback to Firebase console if URL is not available
                val firebaseStorageUrl = "https://console.firebase.google.com/project/writerportfolio-bf611/storage"
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(firebaseStorageUrl))
                startActivity(intent)
                Log.d("CameraFirebase", "Opening Firebase Storage console")
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Could not open image", Toast.LENGTH_SHORT).show()
            Log.e("CameraFirebase", "Failed to open image", e)
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.viewFinder.surfaceProvider)
            }

            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageCapture)
                Log.d("CameraFirebase", "Camera started successfully")
            } catch (e: Exception) {
                Log.e("CameraFirebase", "Use case binding failed", e)
                Toast.makeText(this, "Camera initialization failed", Toast.LENGTH_SHORT).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun getOutputDirectory(): File {
        val mediaDir = externalMediaDirs.firstOrNull()?.let {
            File(it, "CameraFirebase").apply { mkdirs() }
        }
        return if (mediaDir != null && mediaDir.exists()) mediaDir else filesDir
    }

    private fun getRequiredPermissions(): Array<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+ doesn't need READ_EXTERNAL_STORAGE for app-specific directories
            arrayOf(Manifest.permission.CAMERA)
        } else {
            arrayOf(
                Manifest.permission.CAMERA,
                Manifest.permission.READ_EXTERNAL_STORAGE
            )
        }
    }

    private fun allPermissionsGranted(): Boolean {
        return getRequiredPermissions().all {
            ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private val requestPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            Log.d("CameraFirebase", "Permission results: $permissions")

            // Check if all required permissions are granted
            val allGranted = getRequiredPermissions().all { permission ->
                permissions[permission] == true
            }

            if (allGranted) {
                Log.d("CameraFirebase", "All permissions granted, starting camera")
                startCamera()
            } else {
                val deniedPermissions = permissions.filter { !it.value }.keys
                Log.w("CameraFirebase", "Permissions denied: $deniedPermissions")
                Toast.makeText(this, "Camera permission is required to use this app", Toast.LENGTH_LONG).show()
            }
        }

    override fun onBackPressed() {
        if (isPreviewMode) {
            showCameraPreview()
        } else {
            super.onBackPressed()
        }
    }
}