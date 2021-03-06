package com.song.fastmq.storage.storage.impl

import com.song.fastmq.common.utils.OnCompletedObserver
import com.song.fastmq.storage.storage.MessageStorage
import com.song.fastmq.storage.storage.MessageStorageFactory
import com.song.fastmq.storage.storage.MetadataStorage
import com.song.fastmq.storage.storage.OffsetStorage
import com.song.fastmq.storage.storage.config.BookKeeperConfig
import io.reactivex.Observable
import io.reactivex.ObservableEmitter
import org.apache.bookkeeper.client.BookKeeper
import org.apache.bookkeeper.conf.ClientConfiguration
import org.apache.bookkeeper.util.OrderedSafeExecutor
import org.apache.curator.framework.CuratorFramework
import org.apache.curator.framework.CuratorFrameworkFactory
import org.apache.curator.retry.ExponentialBackoffRetry
import org.apache.curator.x.async.AsyncCuratorFramework
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/**
 * Default implementation of [MessageStorageFactory]
 * Created by song on 2017/11/4.
 */
class MessageStorageFactoryImpl @Throws(Exception::class)
constructor(clientConfiguration: ClientConfiguration, private val bookKeeperConfig: BookKeeperConfig) : MessageStorageFactory {

    @Volatile
    private var closed: Boolean = false

    private val bookKeeper: BookKeeper

    private val asyncCuratorFramework: AsyncCuratorFramework

    private val metadataStorage: MetadataStorage

    val offsetStorage: OffsetStorage

    private val messageStorageCache = ConcurrentHashMap<String, MessageStorageImpl>()

    private val curatorFramework: CuratorFramework

    private val messageOrderedThreadPool = OrderedSafeExecutor
            .newBuilder()
//            .name("fastmq-message-workers")
//            .numThreads(20)
            .build()

    init {
        val servers = clientConfiguration.zkServers
        this.bookKeeper = BookKeeper(clientConfiguration)
        val retryPolicy = ExponentialBackoffRetry(1000, 3)
        curatorFramework = CuratorFrameworkFactory.newClient(servers, retryPolicy)
        curatorFramework.start()
        asyncCuratorFramework = AsyncCuratorFramework.wrap(curatorFramework)
        metadataStorage = MetadataStorageImpl(asyncCuratorFramework)
        offsetStorage = ZkOffsetStorageImpl(metadataStorage, asyncCuratorFramework)
    }

    override fun open(topic: String): Observable<MessageStorage> {
        return Observable.create<MessageStorage> { observable: ObservableEmitter<MessageStorage> ->
            if (this.messageStorageCache.contains(topic)) {
                val messageStorage = this.messageStorageCache[topic]
                messageStorage?.run {
                    if (state.get() == MessageStorageImpl.State.CLOSED) {
                        logger.warn("[{}] Message storage is in {} state,removing it from cache to recreate one.", topic, state.get())
                        messageStorageCache.remove(topic)
                    }
                }
            }
            val throwable = AtomicReference<Throwable>()
            val messageStorage = this.messageStorageCache.computeIfAbsent(topic) {
                val ms = MessageStorageImpl(topic, bookKeeper, bookKeeperConfig, metadataStorage, messageOrderedThreadPool)
                ms.initialize()
                        .blockingSubscribe(object : OnCompletedObserver<Void>() {
                            override fun onError(e: Throwable) {
                                throwable.compareAndSet(null, e)
                            }

                            override fun onComplete() {
                            }

                        })
                return@computeIfAbsent ms
            }
            if (throwable.get() != null) {
                observable.onError(throwable.get())
            } else {
                observable.onNext(messageStorage)
                observable.onComplete()
            }
        }
    }

    @Synchronized
    override fun close() {
        if (!closed) {
            this.messageStorageCache.forEach { _, u: MessageStorage -> run { u.close() } }
            this.messageOrderedThreadPool.shutdown()
            this.messageStorageCache.forEach { _: String, u: MessageStorage -> u.close() }
            this.messageStorageCache.clear()
            this.bookKeeper.close()
            this.offsetStorage.close()
            this.curatorFramework.close()
            closed = true
        }
    }

    companion object {

        private val logger = LoggerFactory.getLogger(MessageStorageFactoryImpl::class.java)
    }

}
