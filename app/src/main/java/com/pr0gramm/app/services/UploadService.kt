package com.pr0gramm.app.services

import android.graphics.Bitmap
import com.pr0gramm.app.api.pr0gramm.Api
import com.pr0gramm.app.feed.ContentType
import com.pr0gramm.app.services.config.ConfigService
import com.pr0gramm.app.ui.dialogs.ignoreError
import com.pr0gramm.app.util.*
import com.squareup.picasso.Picasso
import okhttp3.MediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okio.BufferedSink
import retrofit2.HttpException
import rx.Observable
import rx.lang.kotlin.ofType
import rx.subjects.BehaviorSubject
import java.io.*
import java.util.concurrent.TimeUnit


/**
 */
class UploadService(private val api: Api,
                    private val userService: UserService,
                    private val picasso: Picasso,
                    private val configService: ConfigService,
                    private val voteService: VoteService,
                    private val cacheService: InMemoryCacheService) {

    /**
     * Maximum upload size for the currently signed in user.
     */
    private val maxUploadSize: Long
        get() {
            return userService.loginState.let { state ->
                val config = configService.config()
                if (state.premium) config.maxUploadSizePremium else config.maxUploadSizeNormal
            }
        }

    fun sizeOkay(file: File): Observable<Boolean> {
        return Observable.just(maxUploadSize).map { file.length() < it }
    }

    fun downsize(file: File): Observable<File> {
        return Observable.fromCallable {
            logger.info("Try to scale {}kb image down to max of {}kb",
                    file.length() / 1024, maxUploadSize / 1024)

            val bitmap = picasso.load(file)
                    .config(Bitmap.Config.ARGB_8888)
                    .centerInside()
                    .resize(2048, 2048)
                    .onlyScaleDown()
                    .get()

            logger.info("Image loaded at {}x{}px", bitmap.width, bitmap.height)

            // scale down to temp file
            val target = File.createTempFile("upload", "jpg", file.parentFile)
            target.deleteOnExit()

            val format = Bitmap.CompressFormat.JPEG
            var quality = 90
            do {
                FileOutputStream(target).use { output ->
                    logger.info("Compressing to {} at quality={}", format, quality)
                    if (!bitmap.compress(format, quality, output))
                        throw IOException("Could not compress image data")

                    logger.info("Size is now {}kb", target.length() / 1024)
                }

                // decrease quality to shrink even further
                quality -= 10
            } while (target.length() >= maxUploadSize && quality > 30)

            logger.info("Finished downsizing with an image size of {}kb", target.length() / 1024)
            target
        }
    }

    private fun upload(file: File): Observable<State> {
        if (!file.exists() || !file.isFile || !file.canRead())
            return Observable.error(FileNotFoundException("Can not read file to upload"))

        val result = BehaviorSubject.create<State>(State.Uploading(progress = 0f)).toSerialized()

        val output = object : RequestBody() {
            override fun contentType(): MediaType {
                val fallback = MediaType.parse("image/jpeg")!!
                return try {
                    MimeTypeHelper.guess(file)?.let { MediaType.parse(it) } ?: fallback
                } catch (ignored: IOException) {
                    fallback
                }
            }

            override fun contentLength(): Long {
                return file.length()
            }

            override fun writeTo(sink: BufferedSink) {
                val length = file.length().toFloat()

                var lastTime = 0L
                FileInputStream(file).use { input ->
                    // send first progress report.
                    result.onNext(State.Uploading(progress = 0f))

                    var sent = 0
                    readStream(input) { buffer, len ->
                        sink.write(buffer, 0, len)
                        sent += len

                        val now = System.currentTimeMillis()
                        if (now - lastTime >= 100) {
                            lastTime = now

                            // send progress now.
                            val progress = sent / length
                            result.onNext(State.Uploading(progress = progress))
                        }
                    }

                    // tell that the file is sent
                    result.onNext(State.Uploading(progress = 1f))
                }
            }
        }

        val body = MultipartBody.Builder()
                .setType(MediaType.parse("multipart/form-data")!!)
                .addFormDataPart("image", file.name, output)
                .build()

        val size = file.length()

        // perform the upload!
        api.upload(body)
                .doOnEach { Track.upload(size) }
                .map { response -> State.Uploaded(response.key) }
                .subscribeOnBackground()
                .subscribe(result)

        return result.ignoreElements().mergeWith(result)
    }

    private fun post(key: String, contentType: ContentType, tags_: Set<String>,
                     checkSimilar: Boolean): Observable<State> {

        val contentTypeTag = contentType.name.toLowerCase()
        val tags = tags_.map { it.trim() }.filter { isValidTag(it) }

        // we can only post 4 extra tags with an upload, so lets add the rest later
        val firstTags: List<String>
        val extraTags: List<String>

        if (contentType == ContentType.SFW) {
            firstTags = tags.take(5)
            extraTags = tags.drop(5)
        } else {
            // we need to add the nsfw/nsfl tag to the list of tags.
            // It looks like the sfwstatus is getting ignored
            firstTags = listOf(contentTypeTag) + tags.take(4)
            extraTags = tags.drop(4)
        }

        return api
                .post(null, contentTypeTag, firstTags.joinToString(","), checkSimilar.toInt(), key, 1)
                .map { postedToState(key, it) }

                .flatMap { state ->
                    if (state is State.Pending) {
                        waitOnQueue(state.queue)
                    } else {
                        Observable.just(state)
                    }
                }

                .doOnNext { state ->
                    // cache tags so that they appear immediately
                    if (state is State.Success) {
                        cacheService.cacheTags(state.id, tags)
                    }
                }

                .flatMap { state ->
                    if (state is State.Success && extraTags.isNotEmpty()) {
                        // try to add the extra parameters.
                        voteService.tag(state.id, extraTags).ignoreError().ofType<State>()
                                .concatWith(Observable.just(state))

                    } else {
                        Observable.just(state)
                    }
                }
    }

    private fun postedToState(key: String, posted: Api.Posted): State {
        if (posted.similar.isNotEmpty()) {
            return State.SimilarItems(key, posted.similar)
        }

        if (posted.error != null) {
            return State.Error(posted.error, posted.report)
        }

        if (posted.itemId > 0) {
            return State.Success(posted.itemId)
        }

        posted.queueId?.let { queue ->
            return State.Pending(queue, -1)
        }

        throw IllegalStateException("can not map result to state")
    }

    private fun waitOnQueue(queue: Long): Observable<State> {
        return api.queue(queue)
                .map { info ->
                    val itemId = info.item?.id ?: 0
                    when {
                        info.status == "done" && itemId > 0 -> State.Success(itemId)
                        info.status == "processing" -> State.Processing()
                        else -> State.Pending(queue, info.position)
                    }
                }

                .retryWhen { errObservable ->
                    errObservable
                            .doOnNext { err -> logger.warn("Error polling queue", err) }
                            .delay(2, TimeUnit.SECONDS, BackgroundScheduler)
                }

                .flatMap { value ->
                    if (value is State.Success) {
                        Observable.just(value)
                    } else {
                        Observable.just(value).concatWith(waitOnQueue(queue))
                    }
                }

                .delaySubscription(2, TimeUnit.SECONDS, BackgroundScheduler)
    }

    sealed class State {
        class Error(val error: String, val report: Api.Posted.VideoReport?) : State()
        class Uploading(val progress: Float) : State()
        class Uploaded(val key: String) : State()
        class Success(val id: Long) : State()
        class Pending(val queue: Long, val position: Long) : State()
        class Processing : State()
        class SimilarItems(val key: String, val items: List<Api.Posted.SimilarItem>) : State()
    }

    fun upload(file: File, sfw: ContentType, tags: Set<String>): Observable<State> {
        return upload(file).flatMap { state ->
            if (state is State.Uploaded) {
                Observable
                        .just<State>(state)
                        .concatWith(post(state.key, sfw, tags, true))
            } else {
                Observable.just(state)
            }
        }
    }

    fun post(state: State.Uploaded, contentType: ContentType, tags: Set<String>,
             checkSimilar: Boolean): Observable<State> {

        return post(state.key, contentType, tags, checkSimilar)
    }

    /**
     * Checks if the current user is rate limited. Returns true, if the user
     * is not allowed to upload an image right now. Returns false, if the user is
     * allowed to upload an image.
     */
    fun checkIsRateLimited(): Observable<Boolean> {
        return api.ratelimited().map { false }.onErrorResumeNext { error ->
            if (error is HttpException && error.code() == 403) {
                Observable.just(true)
            } else {
                Observable.error(error)
            }
        }
    }

    class UploadInfo(val key: String? = null,
                     val similar: List<Api.Posted.SimilarItem> = emptyList(),
                     val id: Long = 0,
                     val progress: Float = -1F) {

        val finished = id > 0
        val hasSimilar = similar.isNotEmpty()
    }

    class UploadFailedException(message: String, val report: Api.Posted.VideoReport?) : Exception(message) {
        val errorCode: String get() = message!!
    }

    companion object {
        private val logger = logger("UploadService")

    }
}

fun isValidTag(tag: String): Boolean {
    val invalidTags = setOf("sfw", "nsfw", "nsfl", "nsfp", "gif", "webm", "sound")
    val invalid = tag in invalidTags || tag.length < 2 || tag.length > 32
    return !invalid
}

fun isMoreRestrictiveContentTypeTag(tags: List<Api.Tag>, tag: String): Boolean {
    val t = tags.map { it.tag }
    return (tag !in t) && ((tag == "nsfw" && "nsfl" !in t) || (tag == "nsfl"))
}

