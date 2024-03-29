/*
 * Created by Karic Kenan on 2.2.2021
 * Copyright (c) 2021 . All rights reserved.
 */

package io.aethibo.fireshare.data.remote.main

import android.net.Uri
import io.aethibo.fireshare.domain.*
import io.aethibo.fireshare.domain.request.PostRequestBody
import io.aethibo.fireshare.domain.request.ProfileUpdateRequestBody
import io.aethibo.fireshare.framework.utils.Resource

interface MainRemoteDataSource {
    /**
     * Post handler
     */
    suspend fun getPostsForProfile(uid: String): Resource<List<Post>>
    suspend fun createPost(body: PostRequestBody): Resource<Any>
    suspend fun updatePost(body: PostToUpdateBody): Resource<Any>
    suspend fun deletePost(post: Post): Resource<Post>
    suspend fun toggleLikeForPost(post: Post): Resource<Boolean>
    suspend fun getTimeline(): Resource<List<Post>>
    suspend fun getPostsCount(uid: String): Resource<Int>

    /**
     * Users handler
     */
    suspend fun getSingleUser(uid: String): Resource<User>
    suspend fun updateUserProfile(body: ProfileUpdateRequestBody): Resource<Any>
    suspend fun updateProfilePicture(uid: String, imageUri: Uri): Uri?
    suspend fun searchUsers(query: String): Resource<List<User>>
    suspend fun toggleFollowForUser(uid: String): Resource<FollowResponseBody>
    suspend fun checkIfFollowing(uid: String): Resource<FollowResponseBody>
    suspend fun getFollowingCount(uid: String): Resource<Int>
    suspend fun getFollowersCount(uid: String): Resource<Int>

    /**
     * Comments handler
     */
    suspend fun getCommentsForPost(postId: String): Resource<List<Comment>>
    suspend fun createComment(postId: String, comment: String): Resource<Comment>
    suspend fun deleteComment(comment: Comment): Resource<Comment>

    /**
     * Notifications feed
     */
    suspend fun addLikeToFeed(ownerId: String, postId: String, postImage: String): Resource<Any>
    suspend fun removeLikeFromFeed(ownerId: String, postId: String): Resource<Any>
    suspend fun addCommentToFeed(postId: String, commentId: String, ownerId: String, comment: String, postImage: String): Resource<Any>
    suspend fun removeCommentFromFeed(ownerId: String, commentId: String): Resource<Any>
    suspend fun addFollowToFeed(ownerId: String): Resource<Any>
    suspend fun removeFollowFromFeed(ownerId: String): Resource<Any>
    suspend fun getNotificationFeed(): Resource<List<ActivityFeedItem>>
}