package com.pr0gramm.app.services;

import com.pr0gramm.app.api.pr0gramm.Api;
import com.pr0gramm.app.api.pr0gramm.response.Message;
import com.pr0gramm.app.api.pr0gramm.response.MessageFeed;
import com.pr0gramm.app.api.pr0gramm.response.PrivateMessage;
import com.pr0gramm.app.api.pr0gramm.response.PrivateMessageFeed;
import com.pr0gramm.app.api.pr0gramm.response.UserComments;
import com.pr0gramm.app.feed.ContentType;
import com.pr0gramm.app.feed.Nothing;

import org.joda.time.Duration;
import org.joda.time.Instant;

import java.util.List;
import java.util.Set;

import javax.inject.Inject;
import javax.inject.Singleton;

import rx.Observable;
import rx.subjects.BehaviorSubject;

/**
 * Service for receiving and sending private messages
 * as well as the combined inbox (comments and private messages)
 */
@Singleton
public class InboxService {
    private final Api api;

    private final BehaviorSubject<Integer> unreadMessagesCount = BehaviorSubject.create(0);

    @Inject
    public InboxService(Api api) {
        this.api = api;
    }

    /**
     * Gets the list of unread messages. You can not call this multiple times, because
     * it will mark the messages as read immediately.
     */
    public Observable<List<Message>> getUnreadMessages() {
        publishUnreadMessagesCount(0);
        return api.inboxUnread().map(MessageFeed::getMessages);
    }

    /**
     * Gets the list of inbox messages
     */
    public Observable<List<Message>> getInbox() {
        return api.inboxAll().map(MessageFeed::getMessages);
    }

    /**
     * Gets the list of private messages.
     */
    public Observable<List<PrivateMessage>> getPrivateMessages() {
        return api.inboxPrivateMessages().map(PrivateMessageFeed::getMessages);
    }

    /**
     * Gets all the comments a user has written
     */
    public Observable<UserComments> getUserComments(String user, Set<ContentType> contentTypes) {
        return api.userComments(user,
                Instant.now().plus(Duration.standardDays(1)).getMillis() / 1000L,
                ContentType.combine(contentTypes));
    }

    /**
     * Returns an observable that produces the number of unread messages.
     */
    public Observable<Integer> unreadMessagesCount() {
        return unreadMessagesCount.asObservable();
    }

    void publishUnreadMessagesCount(int unreadCount) {
        unreadMessagesCount.onNext(unreadCount);
    }

    /**
     * Sends a private message to a receiver
     */
    public Observable<Nothing> send(long receiverId, String message) {
        return api.sendMessage(null, message, receiverId);
    }
}