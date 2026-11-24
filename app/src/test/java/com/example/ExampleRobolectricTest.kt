package com.example

import android.content.Context
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ModeComment
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.ThumbUp
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AppPreferences
import com.example.data.model.VideoItem
import com.example.data.remote.SupabaseClient
import com.example.data.repository.BladeRepository
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

  private fun sampleVideo(
    id: String,
    category: String = "All",
    title: String = id,
    caption: String = "",
    hashtags: List<String> = emptyList(),
    userId: String = "creator_$id",
    likes: Int = 0,
    views: Int = 0
  ) = VideoItem(
    id = id,
    userId = userId,
    videoUrl = "https://cdn.example.com/$id.mp4",
    title = title,
    caption = caption,
    category = category,
    hashtags = hashtags,
    likesCount = likes,
    viewsCount = views
  )

  @Test
  fun `read string from context and verify CameraMlAnalyzer defers heavy work safely`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("Blade", appName)

    var faceUpdated = false
    val analyzer = com.example.core.camera.CameraMlAnalyzer(
      isFrontCameraProvider = { true },
      isPreviewStreamingProvider = { false },
      needsFaceTrackingProvider = { true },
      needsSegmentationProvider = { false },
      onFaceUpdated = { faceUpdated = true },
      onLumaUpdated = {},
      onMaskUpdated = {}
    )
    analyzer.close()
    assertEquals(false, faceUpdated)
  }

  @Test
  fun `watch time and interactions prioritize same and similar categories next`() = runBlocking {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val prefs = AppPreferences(context)
    val repo = BladeRepository(prefs, SupabaseClient(prefs))

    val cricketWatched = sampleVideo(
      id = "v_cricket_1",
      category = "Cricket",
      title = "Match highlights",
      userId = "u_c1"
    )
    // Simulate long watch + like + comment + share on Cricket
    repo.recordWatchSignal(cricketWatched, weight = 14) // deep watch time
    repo.recordWatchSignal(cricketWatched, weight = 6)  // like
    repo.recordWatchSignal(cricketWatched, weight = 7)  // comment
    repo.recordWatchSignal(cricketWatched, weight = 8)  // share

    val unseenFoodHighViews = sampleVideo("v_food", category = "Food", views = 9000, likes = 800)
    val unseenCricket = sampleVideo("v_cricket_2", category = "Cricket", views = 5, likes = 0)
    val unseenInferredMusic = sampleVideo("v_music", category = "All", title = "Guitar cover #music", views = 10)
    val unseenGamingRelated = sampleVideo("v_gaming", category = "Gaming", views = 10)

    val ranked = repo.rankUpcomingVideos(
      videos = listOf(unseenFoodHighViews, unseenInferredMusic, unseenGamingRelated, unseenCricket),
      forYou = true
    )

    // Matching Cricket video ranks first, and Cricket-related Gaming beats unrelated lower-affinity clips
    assertEquals("v_cricket_2", ranked.first().id)
    assertTrue(ranked.indexOfFirst { it.id == "v_gaming" } < ranked.indexOfFirst { it.id == "v_music" })
  }

  @Test
  fun `reloading For You shows new videos then cycles older videos one by one without repeating top`() = runBlocking {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val prefs = AppPreferences(context)
    val repo = BladeRepository(prefs, SupabaseClient(prefs))

    val catalog = listOf(
      sampleVideo("v1", category = "Comedy", views = 5000, likes = 300),
      sampleVideo("v2", category = "Music", views = 20, likes = 2),
      sampleVideo("v3", category = "Tech", views = 10, likes = 1)
    )

    val topIds = mutableListOf<String>()
    var previousTop: VideoItem? = null

    repeat(6) { step ->
      val reloadState = if (step == 0) {
        repo.getFeedReloadState()
      } else {
        repo.updateFeedReloadState(
          forYou = true,
          newlySurfacedIds = listOfNotNull(previousTop?.id),
          incrementCycle = true,
          nextOffset = 0
        )
      }
      val ranked = repo.rankVideosWithSignals(
        videos = catalog,
        forYou = true,
        followingIds = emptySet(),
        affinity = prefs.getWatchAffinity(),
        demoteIds = reloadState.lastBatchIds.toSet(),
        reloadCycle = reloadState.reloadCycle,
        currentSeq = reloadState.surfacedSeq
      )
      val currentTop = ranked.first()
      previousTop?.let { prev ->
        assertNotEquals("Reload step $step should not repeat top video ${prev.id}", prev.id, currentTop.id)
      }
      topIds.add(currentTop.id)
      prefs.markVideoSeen(currentTop.id)
      previousTop = currentTop
    }

    // All 3 videos must appear across the first 3 reloads (unseen first), then rotate older ones
    assertEquals(setOf("v1", "v2", "v3"), topIds.take(3).toSet())
    assertEquals(setOf("v1", "v2", "v3"), topIds.drop(3).toSet())
  }

  @Test
  fun `admin gate password and credential popup reject wrong inputs and accept valid admin`() = runBlocking {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val prefs = AppPreferences(context)
    val repo = BladeRepository(prefs, SupabaseClient(prefs))

    assertTrue(repo.verifyAdminGatePassword("").isFailure)
    assertTrue(repo.verifyAdminGatePassword("wrong_pass").isFailure)
    assertTrue(repo.authenticateAdmin("blade_admin", "wrong_pass").isFailure)
    assertTrue(repo.authenticateAdmin("wrong_admin", com.example.data.remote.SupabaseConfig.defaultAdminPassword).isFailure)

    val validPass = com.example.data.remote.SupabaseConfig.defaultAdminPassword
    val validId = com.example.data.remote.SupabaseConfig.defaultAdminUserId
    assertTrue(repo.verifyAdminGatePassword(validPass).isSuccess)
    assertTrue(repo.authenticateAdmin(validId, validPass).isSuccess)
  }

  @Test
  fun `createViewModel manages video-only modes front camera bright beauty AR filters timeline undo redo and draft recovery`() = runBlocking {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val prefs = AppPreferences(context)
    val repo = BladeRepository(prefs, SupabaseClient(prefs))
    val vm = com.example.ui.viewmodel.CreateViewModel(repo)

    // Strictly video-only modes (3m, 60s, 15s) and front camera active by default
    assertEquals(3, com.example.ui.viewmodel.CaptureMode.entries.size)
    assertTrue(vm.uiState.value.useFrontCamera)
    assertTrue(vm.uiState.value.beauty.enabled)
    assertTrue(vm.uiState.value.beautyBrightness > 0f)

    vm.setCaptureMode(com.example.ui.viewmodel.CaptureMode.M3)
    assertEquals(180, vm.uiState.value.captureMode.maxDurationSec)
    vm.setCaptureMode(com.example.ui.viewmodel.CaptureMode.S15)
    assertEquals(15, vm.uiState.value.captureMode.maxDurationSec)

    // 29-filter catalog, favorites, intensity, beauty, AR face, green screen
    assertTrue(vm.filterPresets.size >= 29)
    vm.updateFilter("Golden Hour")
    vm.updateFilterIntensity(0.75f)
    assertEquals("Golden Hour", vm.uiState.value.selectedFilter)
    assertEquals(0.75f, vm.uiState.value.filterIntensity)
    val cm = com.example.core.camera.VideoFilterCatalog.computeColorMatrix("Golden Hour", filterIntensity = 0.75f, autoLowLightLift = 0.2f)
    assertEquals(20, cm.array.size)

    vm.toggleFavoriteFilter("sunset")
    assertTrue("sunset" in vm.uiState.value.favoriteFilterIds)

    vm.setSpeedMultiplier(0.3f)
    assertEquals(0.3f, vm.uiState.value.speedMultiplier)
    vm.setTimerSeconds(10)
    assertEquals(10, vm.uiState.value.timerSeconds)
    vm.updateBeauty { it.copy(skinSmoothing = 0.9f, lipstickIntensity = 0.6f, enabled = false) }
    assertEquals(0f, vm.uiState.value.beauty.activeFactor)
    vm.updateBeauty { it.copy(enabled = true) }
    assertTrue(vm.uiState.value.beauty.activeFactor > 0f)

    // Camera defaults keep ML Kit landmarks OFF until AR/makeup is enabled, preventing camera lag
    assertEquals(false, com.example.core.camera.BeautySettings().needsFaceLandmarks)
    assertEquals(true, vm.uiState.value.beauty.needsFaceLandmarks)

    // Audio format validation & trim clamping for MP3/M4A/AAC/WAV
    assertTrue(com.example.core.camera.AudioImportManager.isSupportedAudio("audio/mpeg", "song.mp3"))
    assertTrue(com.example.core.camera.AudioImportManager.isSupportedAudio(null, "beat.m4a"))
    assertTrue(com.example.core.camera.AudioImportManager.isSupportedAudio("audio/wav", "clip.wav"))
    assertEquals(false, com.example.core.camera.AudioImportManager.isSupportedAudio("image/jpeg", "photo.jpg"))

    val customSong = com.example.data.model.SoundItem(
      id = "local_song",
      title = "Summer Beat",
      artist = "Phone Storage",
      audioUrl = "file:///tmp/summer.mp3",
      durationSeconds = 90
    )
    vm.selectSound(customSong, fullDurationMs = 90_000L, isLocalDevice = true)
    assertTrue(vm.uiState.value.isDeviceAudio)
    assertEquals(90_000L, vm.uiState.value.selectedSoundDurationMs)
    vm.updateSoundTrimRange(12_000L, 42_000L)
    assertEquals(12_000L, vm.uiState.value.soundTrimStartMs)
    assertEquals(42_000L, vm.uiState.value.soundTrimEndMs)
    vm.removeSelectedSound()
    assertEquals(null, vm.uiState.value.selectedSound)
    assertEquals(false, vm.uiState.value.isDeviceAudio)

    vm.setArEffect(com.example.core.camera.ArEffectType.CAT_EARS)
    assertEquals(com.example.core.camera.ArEffectType.CAT_EARS, vm.uiState.value.arEffect)

    val preset = vm.greenScreenPresets.first()
    vm.setGreenScreenPreset(preset)
    assertEquals(preset.id, vm.uiState.value.greenScreenPreset?.id)
    assertTrue(vm.uiState.value.greenScreenEnabled)
    vm.clearGreenScreen()
    assertEquals(null, vm.uiState.value.greenScreenPreset)

    // Timeline split, merge, transform, undo/redo, and draft save/recovery
    val testUri = android.net.Uri.parse("file:///tmp/test_blade.mp4")
    vm.onVideoSelected(testUri, 20_000L)
    assertEquals(com.example.ui.viewmodel.CreateStep.EDIT_MEDIA, vm.uiState.value.step)
    assertEquals(1, vm.uiState.value.segments.size)

    vm.updatePlayheadMs(10_000L)
    vm.splitSegmentAtPlayhead()
    assertEquals(2, vm.uiState.value.segments.size)
    assertTrue(vm.uiState.value.canUndo)

    vm.undo()
    assertEquals(1, vm.uiState.value.segments.size)
    assertTrue(vm.uiState.value.canRedo)

    vm.redo()
    assertEquals(2, vm.uiState.value.segments.size)

    vm.mergeVideoSegment(android.net.Uri.parse("file:///tmp/second.mp4"), 10_000L)
    assertEquals(3, vm.uiState.value.segments.size)

    vm.rotate90Degrees()
    vm.setAspectRatioMode("1:1")
    vm.toggleReversePlayback()
    assertEquals(90, vm.uiState.value.rotationDegrees)
    assertEquals("1:1", vm.uiState.value.aspectRatioMode)
    assertTrue(vm.uiState.value.reversePlayback)

    vm.addOverlay("Hello Blade", isSticker = false, animation = com.example.ui.viewmodel.OverlayAnimation.POP)
    vm.updateTitle("My Clip")
    vm.appendHashtag("dhaka")
    vm.saveProjectDraft()

    // Draft preservation across Edit -> Publish -> Edit -> Camera -> Resume Draft
    vm.proceedToPublish()
    assertEquals(com.example.ui.viewmodel.CreateStep.PUBLISH_MEDIA, vm.uiState.value.step)
    vm.backToEdit()
    assertEquals(com.example.ui.viewmodel.CreateStep.EDIT_MEDIA, vm.uiState.value.step)
    vm.backToCapture()
    assertEquals(com.example.ui.viewmodel.CreateStep.CAPTURE, vm.uiState.value.step)
    assertTrue(vm.uiState.value.hasDraftMedia)
    assertEquals("My Clip", vm.uiState.value.title)
    assertTrue(vm.uiState.value.caption.contains("#dhaka"))
    assertEquals(1, vm.uiState.value.overlays.size)
    vm.resumeDraftEdit()
    assertEquals(com.example.ui.viewmodel.CreateStep.EDIT_MEDIA, vm.uiState.value.step)

    // AI repository gracefully reports failure when unreadable video URI is supplied
    val aiRepo = com.example.data.ai.LocalSampleAiEffectsRepository()
    val aiFail = aiRepo.processVideoAiEffect(
      contentResolver = context.contentResolver,
      videoUri = android.net.Uri.parse("file:///non_existent_clip.mp4"),
      durationMs = 10_000L,
      effectType = com.example.data.ai.AiEffectType.ANIME_CARTOON,
      stylePrompt = "cyber",
      onProgress = { _, _ -> }
    )
    assertTrue(aiFail.isFailure)
  }

  @Test
  fun `adminViewModel tracks verification filters and validates video upload target user`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val prefs = AppPreferences(context)
    val repo = BladeRepository(prefs, SupabaseClient(prefs))
    val adminVm = com.example.ui.viewmodel.AdminViewModel(repo)

    adminVm.updateUserFilter(com.example.ui.viewmodel.UserFilter.PENDING)
    assertEquals(com.example.ui.viewmodel.UserFilter.PENDING, adminVm.uiState.value.userFilter)

    adminVm.updateUserFilter(com.example.ui.viewmodel.UserFilter.REJECTED)
    assertEquals(com.example.ui.viewmodel.UserFilter.REJECTED, adminVm.uiState.value.userFilter)

    adminVm.updateUserFilter(com.example.ui.viewmodel.UserFilter.VERIFIED)
    assertEquals(com.example.ui.viewmodel.UserFilter.VERIFIED, adminVm.uiState.value.userFilter)

    // Publishing without a target user surfaces inline uploadError immediately
    adminVm.publishVideoForUser(
      contentResolver = context.contentResolver,
      targetUserId = "",
      targetUsername = "",
      videoUri = null,
      remoteVideoUrl = "https://cdn.example.com/clip.mp4",
      title = "Clip",
      caption = "Test",
      category = "All",
      location = "",
      privacy = "public",
      onPublished = {}
    )
    assertEquals(
      "Select a user account to post this video under.",
      adminVm.uiState.value.uploadError
    )

    adminVm.clearUploadError()
    assertEquals(null, adminVm.uiState.value.uploadError)
  }

  @Test
  fun `inboxViewModel mergeMessages deduplicates clientIds preserves local preview and updates read ticks`() {
    val optimisticImg = com.example.data.model.MessageItem(
      id = "local_cid_img",
      conversationId = "c1",
      senderId = "u1",
      clientId = "cid_img",
      messageType = "image",
      mediaUrl = "content://picker/photo.jpg",
      status = "sending",
      createdAt = "2026-11-19T10:00:00Z",
      localPreviewUri = "content://picker/photo.jpg"
    )
    val failedText = com.example.data.model.MessageItem(
      id = "local_cid_fail",
      conversationId = "c1",
      senderId = "u1",
      clientId = "cid_fail",
      content = "Retry me",
      status = "failed",
      createdAt = "2026-11-19T10:00:05Z"
    )

    val serverInserted = com.example.data.model.MessageItem(
      id = "srv_1",
      conversationId = "c1",
      senderId = "u1",
      clientId = "cid_img",
      messageType = "image",
      mediaUrl = "https://cdn.example.com/photo.jpg",
      status = "sent",
      createdAt = "2026-11-19T10:00:01Z"
    )
    val duplicateServer = serverInserted.copy()

    val mergedAfterSend = com.example.ui.viewmodel.InboxViewModel.mergeMessages(
      current = listOf(optimisticImg, failedText),
      incoming = listOf(serverInserted, duplicateServer)
    )
    // Duplicate client_id & duplicate server row collapsed cleanly; failed message stays queued at bottom
    assertEquals(2, mergedAfterSend.size)
    assertEquals("srv_1", mergedAfterSend[0].id)
    assertEquals("sent", mergedAfterSend[0].resolvedStatus)
    assertEquals("content://picker/photo.jpg", mergedAfterSend[0].localPreviewUri)
    assertEquals("local_cid_fail", mergedAfterSend[1].id)

    // Realtime UPDATE turns Single Tick -> Double Tick -> Read Tick without duplication
    val deliveredUpdate = serverInserted.copy(
      status = "delivered",
      deliveredAt = "2026-11-19T10:00:02Z"
    )
    val readUpdate = deliveredUpdate.copy(
      status = "read",
      isRead = true,
      readAt = "2026-11-19T10:00:03Z"
    )
    val mergedAfterRead = com.example.ui.viewmodel.InboxViewModel.mergeMessages(
      current = mergedAfterSend,
      incoming = listOf(deliveredUpdate, readUpdate)
    )
    assertEquals(2, mergedAfterRead.size)
    assertEquals("read", mergedAfterRead[0].resolvedStatus)

    // Stale poll returning older "delivered" state must never downgrade a "read" tick
    val mergedAfterStalePoll = com.example.ui.viewmodel.InboxViewModel.mergeMessages(
      current = mergedAfterRead,
      incoming = listOf(deliveredUpdate)
    )
    assertEquals(2, mergedAfterStalePoll.size)
    assertEquals("read", mergedAfterStalePoll[0].resolvedStatus)
  }

  @Test
  fun `inboxViewModel mergeMessages coalesces legacy DB realtime row without clientId when send returns`() {
    val optimistic = com.example.data.model.MessageItem(
      id = "local_cid_legacy",
      conversationId = "c1",
      senderId = "u1",
      clientId = "cid_legacy",
      content = "Legacy hello",
      status = "sending",
      createdAt = "2026-11-19T10:00:00Z"
    )
    // Realtime INSERT from a legacy table has no client_id column yet
    val realtimeWithoutClientId = com.example.data.model.MessageItem(
      id = "srv_legacy_1",
      conversationId = "c1",
      senderId = "u1",
      clientId = "",
      content = "Legacy hello",
      status = "sent",
      createdAt = "2026-11-19T10:00:01Z"
    )
    val afterRealtime = com.example.ui.viewmodel.InboxViewModel.mergeMessages(
      current = listOf(optimistic),
      incoming = listOf(realtimeWithoutClientId)
    )
    // Then sendMessageLegacy returns the inserted row with local clientId attached
    val sendResponse = realtimeWithoutClientId.copy(clientId = "cid_legacy")
    val afterSendCompletes = com.example.ui.viewmodel.InboxViewModel.mergeMessages(
      current = afterRealtime,
      incoming = listOf(sendResponse)
    )
    assertEquals(1, afterSendCompletes.size)
    assertEquals("srv_legacy_1", afterSendCompletes[0].id)
    assertEquals("cid_legacy", afterSendCompletes[0].clientId)
  }

  @Test
  fun `createUiState defaults to Original filter and resets cached upload keys cleanly`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val prefs = AppPreferences(context)
    val repo = BladeRepository(prefs, SupabaseClient(prefs))
    val vm = com.example.ui.viewmodel.CreateViewModel(repo)

    assertEquals("Original", vm.uiState.value.selectedFilter)
    assertEquals(true, vm.uiState.value.useFrontCamera)
    assertEquals(null, vm.uiState.value.pendingUploadKey)
    assertEquals(null, vm.uiState.value.uploadedVideoUrl)

    val uri = android.net.Uri.parse("file:///tmp/sample_hd.mp4")
    vm.onVideoSelected(uri, 12_000L)
    assertEquals(com.example.ui.viewmodel.CreateStep.EDIT_MEDIA, vm.uiState.value.step)
    vm.proceedToPublish()
    assertEquals(com.example.ui.viewmodel.CreateStep.PUBLISH_MEDIA, vm.uiState.value.step)
    vm.backToEdit()
    assertEquals(null, vm.uiState.value.pendingUploadKey)
    assertEquals(null, vm.uiState.value.uploadedVideoUrl)
  }

  @Test
  fun `feedViewModel prependPublishedVideo prepends uploaded video immediately without duplicates`() {
    val existing = listOf(
      sampleVideo(id = "vid_old_1", title = "Older Clip", category = "Comedy")
    )
    val uploaded = sampleVideo(id = "vid_new_1080p", title = "Fresh Clip", category = "Music")

    val afterPublish = com.example.ui.viewmodel.FeedViewModel.prependPublishedVideo(existing, uploaded)
    val afterDuplicateSync = com.example.ui.viewmodel.FeedViewModel.prependPublishedVideo(afterPublish, uploaded)

    assertEquals(2, afterDuplicateSync.size)
    assertEquals("vid_new_1080p", afterDuplicateSync.first().id)
    assertEquals(1, afterDuplicateSync.count { it.id == "vid_new_1080p" })
  }

  @Test
  fun `authViewModel sanitizes activity cancellation errors and exposes browser OAuth fallback`() = runBlocking {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val prefs = AppPreferences(context)
    val repo = BladeRepository(prefs, SupabaseClient(prefs))
    val authVm = com.example.ui.viewmodel.AuthViewModel(repo)

    authVm.onGoogleCredentialError(
      rawMessage = "activity is cancelled by the user.",
      canUseBrowserFallback = true
    )
    assertEquals(false, authVm.uiState.value.isGoogleLoading)
    assertTrue(authVm.uiState.value.offerBrowserGoogleSignIn)
    assertEquals(
      false,
      authVm.uiState.value.errorMessage?.contains("activity is cancelled by the user", ignoreCase = true) == true
    )

    authVm.onGoogleCredentialCancelled(
      fastSystemCancel = true,
      canUseBrowserFallback = true
    )
    assertEquals(null, authVm.uiState.value.errorMessage)
    assertTrue(authVm.uiState.value.offerBrowserGoogleSignIn)
    assertTrue(authVm.uiState.value.infoMessage?.isNotBlank() == true)
  }

  @get:org.junit.Rule
  val composeTestRule = androidx.compose.ui.test.junit4.createComposeRule()

  @Test
  fun `end-to-end country assignment Bangladesh visibility cross-country isolation and adult safety filter`(): Unit = runBlocking {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val prefs = AppPreferences(context)
    prefs.clearSession()

    val fakeServer = FakeSupabaseInterceptor()
    com.example.data.remote.SupabaseConfig.testUrlOverride = "https://test.supabase.co"
    com.example.data.remote.SupabaseConfig.testAnonKeyOverride = "test-anon-key"

    try {
      val repo = BladeRepository(prefs, SupabaseClient(prefs, fakeServer))

      // 1. Create User 1 with country = Bangladesh (no phone required)
      val user1Res = repo.signUpWithEmail(
        name = "Araf Dhaka",
        email = "araf_bd@blade.app",
        password = "password123",
        country = "Bangladesh"
      )
      assertTrue("User 1 signup should succeed: ${user1Res.exceptionOrNull()}", user1Res.isSuccess)
      val user1 = user1Res.getOrThrow()
      assertEquals("Bangladesh", user1.country)

      // Create safe portrait photo file on disk so VideoContentModerator & uploadToStorage run for real
      val safeBitmap = android.graphics.Bitmap.createBitmap(64, 96, android.graphics.Bitmap.Config.ARGB_8888)
      val safeCanvas = android.graphics.Canvas(safeBitmap)
      safeCanvas.drawColor(android.graphics.Color.rgb(28, 52, 96))
      val safeFile = java.io.File(context.cacheDir, "safe_dhaka_sunset.jpg")
      safeFile.outputStream().use { out ->
        safeBitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, out)
      }
      safeBitmap.recycle()

      // 2 & 3. Upload a normal video; country must be auto-assigned as Bangladesh without asking user
      val uploadRes = repo.publishVideo(
        contentResolver = context.contentResolver,
        videoUri = android.net.Uri.fromFile(safeFile),
        title = "Dhaka Evening",
        caption = "Padma river breeze #dhaka",
        category = "Travel",
        location = "Dhaka",
        privacy = "public",
        sound = null,
        filterName = "Original",
        trimStartMs = 0L,
        trimEndMs = 0L,
        durationMs = 5_000L,
        isPhoto = true,
        onProgress = {}
      )
      assertTrue("Normal upload should succeed: ${uploadRes.exceptionOrNull()}", uploadRes.isSuccess)
      val published = uploadRes.getOrThrow()
      assertEquals("Bangladesh", published.country)
      assertEquals("Bangladesh", fakeServer.videos.single().country)

      // Create visually explicit unclothed center-torso + pelvic skin image on dark background
      val explicitBitmap = android.graphics.Bitmap.createBitmap(80, 120, android.graphics.Bitmap.Config.ARGB_8888)
      for (y in 0 until 120) {
        for (x in 0 until 80) {
          val c = if (x in 12 until 68 && y in 20 until 110) {
            if ((x + y) % 3 == 0) android.graphics.Color.rgb(188, 134, 100)
            else android.graphics.Color.rgb(212, 158, 124)
          } else {
            android.graphics.Color.rgb(22, 26, 36)
          }
          explicitBitmap.setPixel(x, y, c)
        }
      }
      val explicitFile = java.io.File(context.cacheDir, "explicit_porn_test.jpg")
      explicitFile.outputStream().use { out ->
        explicitBitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, out)
      }
      explicitBitmap.recycle()

      // 8 & 9. Genuine pornographic visual content must be blocked strictly
      val blockedUploadRes = repo.publishVideo(
        contentResolver = context.contentResolver,
        videoUri = android.net.Uri.fromFile(explicitFile),
        title = "Weekend Clip",
        caption = "Watch this clip",
        category = "All",
        location = "",
        privacy = "public",
        sound = null,
        filterName = "Original",
        trimStartMs = 0L,
        trimEndMs = 0L,
        durationMs = 5_000L,
        isPhoto = true,
        onProgress = {}
      )
      explicitFile.delete()
      assertTrue("Pornographic upload must be blocked", blockedUploadRes.isFailure)
      assertTrue(
        blockedUploadRes.exceptionOrNull()?.message?.contains("Pornographic", ignoreCase = true) == true
      )

      // Suggestive / keyword-only caption with a safe visual must NOT trigger false positives
      val suggestiveSafeUploadRes = repo.publishVideo(
        contentResolver = context.contentResolver,
        videoUri = android.net.Uri.fromFile(safeFile),
        title = "Sexy Dance Challenge #18+",
        caption = "Fitness & bikini beach vibes #dance #modeling",
        category = "Dance",
        location = "Dhaka",
        privacy = "public",
        sound = null,
        filterName = "Original",
        trimStartMs = 0L,
        trimEndMs = 0L,
        durationMs = 5_000L,
        isPhoto = true,
        onProgress = {}
      )
      assertTrue(
        "Normal visual with suggestive/keyword caption must upload cleanly: ${suggestiveSafeUploadRes.exceptionOrNull()}",
        suggestiveSafeUploadRes.isSuccess
      )

      // Verify Bangladesh uploader sees their uploaded videos immediately in feed & discover
      val uploaderFeed = repo.fetchFeed(forYou = true).getOrThrow()
      assertTrue(published.id in uploaderFeed.map { it.id })
      val uploaderDiscover = repo.searchDiscover("", "All", null, null).getOrThrow().first
      assertTrue(published.id in uploaderDiscover.map { it.id })

      // 4, 5 & 6. Sign out, pollute stale local country prefs, restart with new repo instance,
      // and log in as another Bangladesh user ("bangladesh" lowercase to test case-insensitivity)
      repo.signOut()
      val restartedRepo = BladeRepository(prefs, SupabaseClient(prefs, fakeServer))
      val user2Res = restartedRepo.signUpWithEmail(
        name = "Nabila Chittagong",
        email = "nabila_bd@blade.app",
        password = "password123",
        country = "bangladesh"
      )
      assertTrue(user2Res.isSuccess)
      // Simulate stale local DataStore country from an unrelated session before cold-start restore
      prefs.saveLocationPreferences(com.example.data.model.LocationPreferences(country = "India"))
      val restoredUser2 = restartedRepo.restoreCurrentUser()
      assertEquals("Bangladesh", restoredUser2?.country)

      val bd2Feed = restartedRepo.fetchFeed(forYou = true).getOrThrow()
      assertTrue(
        "Second Bangladesh user must see User 1's Bangladesh video instead of 'No videos available'",
        bd2Feed.any { it.id == published.id }
      )
      val bd2Discover = restartedRepo.searchDiscover("", "All", null, null).getOrThrow().first
      assertTrue(bd2Discover.any { it.id == published.id })

      // 7. User from a different country (India) must NOT see the Bangladesh video
      restartedRepo.signOut()
      val indiaUserRes = restartedRepo.signUpWithEmail(
        name = "Rohan Mumbai",
        email = "rohan_in@blade.app",
        password = "password123",
        country = "India"
      )
      assertTrue(indiaUserRes.isSuccess)
      val indiaFeed = restartedRepo.fetchFeed(forYou = true).getOrThrow()
      assertEquals(0, indiaFeed.count { it.id == published.id })
      val indiaDiscover = restartedRepo.searchDiscover("", "All", null, null).getOrThrow().first
      assertEquals(0, indiaDiscover.count { it.id == published.id })

      // Missing country fallback returns MissingCountryException instead of Success(emptyList())
      restartedRepo.signOut()
      fakeServer.insertBareUser(id = "user_no_country", email = "nocountry@blade.app", country = "")
      val noCountryLogin = restartedRepo.signInWithEmail("nocountry@blade.app", "password123")
      assertTrue(noCountryLogin.isSuccess)
      val missingCountryFeed = restartedRepo.fetchFeed(forYou = true)
      assertTrue(missingCountryFeed.exceptionOrNull() is com.example.data.repository.MissingCountryException)

      safeFile.delete()
    } finally {
      prefs.clearSession()
      com.example.data.remote.SupabaseConfig.testUrlOverride = null
      com.example.data.remote.SupabaseConfig.testAnonKeyOverride = null
    }
  }

  @Test
  fun `compose UI verifies signup hides phone field action buttons render cleanly and missing country shows action`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val prefs = AppPreferences(context)
    val repo = BladeRepository(prefs, SupabaseClient(prefs))
    val authVm = com.example.ui.viewmodel.AuthViewModel(repo).apply {
      setMode(com.example.ui.viewmodel.AuthMode.SIGN_UP)
    }
    val adminVm = com.example.ui.viewmodel.AdminViewModel(repo)

    var selectCountryClicks = 0
    var retryClicks = 0

    composeTestRule.setContent {
      com.example.ui.theme.BladeTheme {
        androidx.compose.foundation.layout.Column {
          androidx.compose.foundation.layout.Box(
            modifier = androidx.compose.ui.Modifier.weight(1f)
          ) {
            com.example.ui.screens.AuthScreen(
              authViewModel = authVm,
              adminViewModel = adminVm,
              onBack = {},
              onAuthenticated = {},
              onAdminAuthenticated = {}
            )
          }
          androidx.compose.foundation.layout.Row {
            com.example.ui.components.VideoRailActionButton(
              icon = androidx.compose.material.icons.Icons.Rounded.ThumbUp,
              tint = com.example.ui.theme.TextWhite,
              label = "12",
              onClick = {},
              labelTestTag = "test_like_count"
            )
            com.example.ui.components.VideoRailActionButton(
              icon = androidx.compose.material.icons.Icons.Rounded.ModeComment,
              tint = com.example.ui.theme.TextWhite,
              label = "4",
              onClick = {},
              labelTestTag = "test_comment_count"
            )
            com.example.ui.components.VideoRailActionButton(
              icon = androidx.compose.material.icons.Icons.Rounded.Share,
              tint = com.example.ui.theme.TextWhite,
              label = "2",
              onClick = {},
              labelTestTag = "test_share_count"
            )
          }
          androidx.compose.foundation.layout.Box(
            modifier = androidx.compose.ui.Modifier.weight(0.5f)
          ) {
            com.example.ui.screens.EmptyOrErrorFeedState(
              title = "Select Country",
              subtitle = "Your account country is not set.",
              isSupabaseConfigured = true,
              showSelectCountryAction = true,
              onSelectCountry = { selectCountryClicks++ },
              onRetry = { retryClicks++ }
            )
          }
        }
      }
    }

    composeTestRule.onNode(androidx.compose.ui.test.hasTestTag("signup_country_button")).assertExists()
    composeTestRule.onNode(androidx.compose.ui.test.hasTestTag("signup_email_input")).assertExists()
    composeTestRule.onNode(androidx.compose.ui.test.hasTestTag("auth_phone_input")).assertDoesNotExist()
    composeTestRule.onNode(androidx.compose.ui.test.hasText("Phone Number")).assertDoesNotExist()

    composeTestRule.onNode(androidx.compose.ui.test.hasTestTag("test_like_count")).assertExists()
    composeTestRule.onNode(androidx.compose.ui.test.hasTestTag("test_comment_count")).assertExists()
    composeTestRule.onNode(androidx.compose.ui.test.hasTestTag("test_share_count")).assertExists()

    composeTestRule.onNode(androidx.compose.ui.test.hasTestTag("home_select_country_button")).performClick()
    composeTestRule.onNode(androidx.compose.ui.test.hasTestTag("home_retry_button")).performClick()
    assertEquals(1, selectCountryClicks)
    assertEquals(1, retryClicks)
  }

  @Test
  fun `homepage VideoPlayerBox enforces 9by16 top-aligned frame immediately at top with bottom empty space`() {
    composeTestRule.setContent {
      com.example.ui.theme.BladeTheme {
        androidx.compose.foundation.layout.Box(
          modifier = androidx.compose.ui.Modifier
            .size(width = 360.dp, height = 740.dp)
        ) {
          com.example.ui.components.VideoPlayerBox(
            videoUri = "https://cdn.example.com/sample.jpg",
            isActive = false,
            isPhoto = true,
            enforce9by16Contain = true
          )
        }
      }
    }
    composeTestRule.waitForIdle()
    val frameBounds = composeTestRule
      .onNode(androidx.compose.ui.test.hasTestTag("home_video_player_frame"))
      .fetchSemanticsNode()
      .boundsInRoot
    assertEquals(0f, frameBounds.top, 1f)
    val expectedRatio = 9f / 16f
    val actualRatio = frameBounds.width / frameBounds.height
    assertTrue(
      "Expected 9:16 aspect ratio (~0.5625), got $actualRatio (${frameBounds.width}x${frameBounds.height})",
      kotlin.math.abs(actualRatio - expectedRatio) < 0.02f
    )
  }

  @Test
  fun `legacy database missing videos country column succeeds upload and preserves Bangladesh filtering`(): Unit = runBlocking {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val prefs = AppPreferences(context)
    prefs.clearSession()

    val fakeServer = FakeSupabaseInterceptor(simulateMissingVideosCountryColumn = true)
    com.example.data.remote.SupabaseConfig.testUrlOverride = "https://test.supabase.co"
    com.example.data.remote.SupabaseConfig.testAnonKeyOverride = "test-anon-key"

    try {
      val repo = BladeRepository(prefs, SupabaseClient(prefs, fakeServer))
      val userRes = repo.signUpWithEmail(
        name = "Tariq Dhaka",
        email = "tariq_bd@blade.app",
        password = "password123",
        country = "Bangladesh"
      )
      assertTrue("Signup should succeed: ${userRes.exceptionOrNull()}", userRes.isSuccess)

      val safeBitmap = android.graphics.Bitmap.createBitmap(64, 96, android.graphics.Bitmap.Config.ARGB_8888)
      safeBitmap.eraseColor(android.graphics.Color.rgb(24, 58, 96))
      val safeFile = java.io.File(context.cacheDir, "legacy_schema_upload.jpg")
      safeFile.outputStream().use { out ->
        safeBitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, out)
      }
      safeBitmap.recycle()

      val uploadRes = repo.publishVideo(
        contentResolver = context.contentResolver,
        videoUri = android.net.Uri.fromFile(safeFile),
        title = "Padma Sunset",
        caption = "Evening vibes #dhaka",
        category = "Travel",
        location = "",
        privacy = "public",
        sound = null,
        filterName = "Original",
        trimStartMs = 0L,
        trimEndMs = 0L,
        durationMs = 5_000L,
        isPhoto = true,
        onProgress = {}
      )
      safeFile.delete()

      assertTrue("Upload must succeed even when videos.country is missing: ${uploadRes.exceptionOrNull()}", uploadRes.isSuccess)
      val uploaded = uploadRes.getOrThrow()
      assertEquals("Bangladesh", uploaded.country)

      // Fetch feed as Bangladesh user -> sees video via creator country / location fallback
      val bdFeed = repo.fetchFeed(forYou = true).getOrThrow()
      assertEquals(listOf(uploaded.id), bdFeed.map { it.id })

      // India user must NOT see the Bangladesh video
      repo.signOut()
      repo.signUpWithEmail(
        name = "Riya Mumbai",
        email = "riya_in@blade.app",
        password = "password123",
        country = "India"
      ).getOrThrow()
      val indiaFeed = repo.fetchFeed(forYou = true).getOrThrow()
      assertTrue("India user must not see Bangladesh video", indiaFeed.isEmpty())
    } finally {
      com.example.data.remote.SupabaseConfig.testUrlOverride = null
      com.example.data.remote.SupabaseConfig.testAnonKeyOverride = null
      prefs.clearSession()
    }
  }

  private class FakeSupabaseInterceptor(
    private val simulateMissingVideosCountryColumn: Boolean = false
  ) : okhttp3.Interceptor {
    data class StoredAccount(
      val id: String,
      val email: String,
      var displayName: String,
      var username: String,
      var country: String
    )

    data class StoredVideo(
      val id: String,
      val userId: String,
      val videoUrl: String,
      val thumbnailUrl: String,
      val title: String,
      val caption: String,
      val category: String,
      val location: String,
      var country: String,
      val privacy: String
    )

    private val accounts = mutableListOf<StoredAccount>()
    val videos = mutableListOf<StoredVideo>()
    private var nextUid = 1
    private var nextVid = 1

    fun insertBareUser(id: String, email: String, country: String) {
      accounts.add(
        StoredAccount(
          id = id,
          email = email,
          displayName = "No Country",
          username = "nocountry_${id.take(4)}",
          country = country
        )
      )
    }

    private fun jsonResp(req: okhttp3.Request, code: Int, bodyJson: String): okhttp3.Response {
      return okhttp3.Response.Builder()
        .request(req)
        .protocol(okhttp3.Protocol.HTTP_1_1)
        .code(code)
        .message(if (code in 200..299) "OK" else "ERR")
        .body(bodyJson.toResponseBody("application/json".toMediaType()))
        .build()
    }

    private fun currentUidFrom(req: okhttp3.Request): String? {
      val auth = req.header("Authorization").orEmpty().removePrefix("Bearer ").trim()
      return if (auth.startsWith("tok_")) auth.removePrefix("tok_") else null
    }

    private fun accountToJson(a: StoredAccount): String {
      return """{"id":"${a.id}","username":"${a.username}","display_name":"${a.displayName}","email":"${a.email}","country":"${a.country}"}"""
    }

    private fun videoToJson(v: StoredVideo): String {
      val creator = accounts.firstOrNull { it.id == v.userId }
      val creatorJson = creator?.let { accountToJson(it) } ?: "null"
      return """{"id":"${v.id}","user_id":"${v.userId}","video_url":"${v.videoUrl}","thumbnail_url":"${v.thumbnailUrl}","title":"${v.title}","caption":"${v.caption}","category":"${v.category}","location":"${v.location}","country":"${v.country}","privacy":"${v.privacy}","users":$creatorJson}"""
    }

    private fun videoToJsonWithoutCreator(v: StoredVideo): String {
      return """{"id":"${v.id}","user_id":"${v.userId}","video_url":"${v.videoUrl}","thumbnail_url":"${v.thumbnailUrl}","title":"${v.title}","caption":"${v.caption}","category":"${v.category}","location":"${v.location}","country":"${v.country}","privacy":"${v.privacy}"}"""
    }

    override fun intercept(chain: okhttp3.Interceptor.Chain): okhttp3.Response {
      val req = chain.request()
      val path = req.url.encodedPath
      val bodyStr = req.body?.let { body ->
        val buf = okio.Buffer()
        body.writeTo(buf)
        buf.readUtf8()
      }.orEmpty()

      if (path.endsWith("/auth/v1/signup")) {
        val json = org.json.JSONObject(bodyStr)
        val email = json.optString("email")
        val data = json.optJSONObject("data")
        val uid = "uid_${nextUid++}"
        val name = data?.optString("display_name").orEmpty()
        val country = data?.optString("country").orEmpty()
        accounts.add(
          StoredAccount(
            id = uid,
            email = email,
            displayName = name,
            username = "user_$uid",
            country = country
          )
        )
        return jsonResp(
          req,
          200,
          """{"access_token":"tok_$uid","refresh_token":"ref_$uid","user":{"id":"$uid","email":"$email","user_metadata":{"display_name":"$name","country":"$country"}}}"""
        )
      }

      if (path.endsWith("/auth/v1/token")) {
        val json = org.json.JSONObject(bodyStr)
        val email = json.optString("email")
        val acc = accounts.firstOrNull { it.email.equals(email, ignoreCase = true) }
          ?: return jsonResp(req, 400, """{"message":"Invalid login credentials"}""")
        return jsonResp(
          req,
          200,
          """{"access_token":"tok_${acc.id}","refresh_token":"ref_${acc.id}","user":{"id":"${acc.id}","email":"${acc.email}","user_metadata":{"display_name":"${acc.displayName}","country":"${acc.country}"}}}"""
        )
      }

      if (path.endsWith("/auth/v1/user")) {
        val uid = currentUidFrom(req) ?: return jsonResp(req, 401, """{"message":"Unauthorized"}""")
        val acc = accounts.firstOrNull { it.id == uid } ?: return jsonResp(req, 404, """{}""")
        return jsonResp(
          req,
          200,
          """{"id":"${acc.id}","email":"${acc.email}","user_metadata":{"display_name":"${acc.displayName}","country":"${acc.country}"}}"""
        )
      }

      if (path.endsWith("/auth/v1/logout")) {
        return jsonResp(req, 200, "{}")
      }

      if (path.contains("/storage/v1/object/")) {
        return jsonResp(req, 200, """{"Key":"videos/uploaded"}""")
      }

      if (path.endsWith("/rest/v1/app_settings")) {
        return jsonResp(
          req,
          200,
          """[{"key":"allow_registrations","value":"true"},{"key":"allow_video_uploads","value":"true"},{"key":"allow_comments","value":"true"}]"""
        )
      }

      if (path.endsWith("/rest/v1/users")) {
        return when (req.method) {
          "GET" -> {
            val idFilter = req.url.queryParameter("id").orEmpty()
            val emailFilter = req.url.queryParameter("email").orEmpty()
            val filtered = accounts.filter { acc ->
              when {
                idFilter.startsWith("eq.") -> acc.id == idFilter.removePrefix("eq.")
                idFilter.startsWith("in.(") -> acc.id in idFilter.removePrefix("in.(").removeSuffix(")").split(",")
                emailFilter.startsWith("ilike.") -> acc.email.equals(emailFilter.removePrefix("ilike."), ignoreCase = true)
                else -> true
              }
            }
            jsonResp(req, 200, filtered.joinToString(",", "[", "]") { accountToJson(it) })
          }
          "POST" -> {
            val json = org.json.JSONObject(bodyStr)
            val id = json.optString("id")
            val existing = accounts.firstOrNull { it.id == id }
            if (existing != null) {
              if (json.has("country") && json.optString("country").isNotBlank()) {
                existing.country = json.optString("country")
              }
              jsonResp(req, 200, "[${accountToJson(existing)}]")
            } else {
              val created = StoredAccount(
                id = id,
                email = json.optString("email"),
                displayName = json.optString("display_name"),
                username = json.optString("username"),
                country = json.optString("country")
              )
              accounts.add(created)
              jsonResp(req, 201, "[${accountToJson(created)}]")
            }
          }
          "PATCH" -> {
            val uid = req.url.queryParameter("id").orEmpty().removePrefix("eq.")
            val json = org.json.JSONObject(bodyStr)
            val acc = accounts.firstOrNull { it.id == uid }
            if (acc != null && json.has("country")) {
              acc.country = json.optString("country")
            }
            jsonResp(req, 200, "[]")
          }
          else -> jsonResp(req, 200, "[]")
        }
      }

      if (path.endsWith("/rest/v1/rpc/get_eligible_feed_videos")) {
        if (simulateMissingVideosCountryColumn) {
          return jsonResp(req, 404, """{"code":"PGRST202","message":"Could not find the function public.get_eligible_feed_videos"}""")
        }
        val json = org.json.JSONObject(bodyStr.ifBlank { "{}" })
        val uid = currentUidFrom(req)
        val userCountry = accounts.firstOrNull { it.id == uid }?.country?.trim().orEmpty()
          .ifBlank { json.optString("p_country").trim() }
        val matching = videos.filter { vid ->
          userCountry.isBlank() || vid.country.trim().equals(userCountry, ignoreCase = true)
        }
        return jsonResp(req, 200, matching.joinToString(",", "[", "]") { videoToJson(it) })
      }

      if (path.endsWith("/rest/v1/videos")) {
        return when (req.method) {
          "GET" -> {
            val videoUrlFilter = req.url.queryParameter("video_url").orEmpty()
            val countryFilter = req.url.queryParameter("country").orEmpty()
            if (simulateMissingVideosCountryColumn && countryFilter.isNotBlank()) {
              return jsonResp(
                req,
                400,
                """{"code":"PGRST204","details":null,"hint":null,"message":"Could not find the 'country' column of 'videos' in the schema cache"}"""
              )
            }
            val selectParam = req.url.queryParameter("select").orEmpty()
            val includeUsers = selectParam.contains("users(")
            val filtered = videos.filter { vid ->
              val urlOk = if (videoUrlFilter.startsWith("eq.")) vid.videoUrl == videoUrlFilter.removePrefix("eq.") else true
              val countryOk = if (countryFilter.startsWith("ilike.")) {
                vid.country.trim().equals(countryFilter.removePrefix("ilike.").trim(), ignoreCase = true)
              } else {
                true
              }
              urlOk && countryOk
            }
            jsonResp(
              req,
              200,
              filtered.joinToString(",", "[", "]") { vid ->
                if (includeUsers) videoToJson(vid) else videoToJsonWithoutCreator(vid)
              }
            )
          }
          "POST" -> {
            val json = org.json.JSONObject(bodyStr)
            if (simulateMissingVideosCountryColumn && (json.has("country") || json.has("title"))) {
              val missingCol = if (json.has("country")) "country" else "title"
              return jsonResp(
                req,
                400,
                """{"code":"PGRST204","details":null,"hint":null,"message":"Could not find the '$missingCol' column of 'videos' in the schema cache"}"""
              )
            }
            val uid = json.optString("user_id")
            val reqCountry = if (simulateMissingVideosCountryColumn) {
              ""
            } else {
              json.optString("country").trim().ifBlank {
                accounts.firstOrNull { it.id == uid }?.country?.trim().orEmpty()
              }
            }
            val created = StoredVideo(
              id = "vid_${nextVid++}",
              userId = uid,
              videoUrl = json.optString("video_url"),
              thumbnailUrl = json.optString("thumbnail_url"),
              title = json.optString("title"),
              caption = json.optString("caption"),
              category = json.optString("category", "All"),
              location = json.optString("location"),
              country = reqCountry,
              privacy = json.optString("privacy", "public")
            )
            videos.add(created)
            jsonResp(req, 201, "[${videoToJson(created)}]")
          }
          else -> jsonResp(req, 200, "[]")
        }
      }

      return jsonResp(req, 200, "[]")
    }
  }
}
