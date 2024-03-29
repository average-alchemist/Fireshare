/*
 * Created by Karic Kenan on 2.2.2021
 * Copyright (c) 2021 . All rights reserved.
 */

package io.aethibo.fireshare.framework.datasource.main

import android.net.Uri
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import io.aethibo.fireshare.data.remote.main.MainRemoteDataSource
import io.aethibo.fireshare.domain.*
import io.aethibo.fireshare.domain.request.PostRequestBody
import io.aethibo.fireshare.domain.request.ProfileUpdateRequestBody
import io.aethibo.fireshare.framework.utils.AppConst
import io.aethibo.fireshare.framework.utils.FirebaseUtil.auth
import io.aethibo.fireshare.framework.utils.FirebaseUtil.storage
import io.aethibo.fireshare.framework.utils.Resource
import io.aethibo.fireshare.framework.utils.safeCall
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.util.*

class MainRemoteDataSourceImpl : MainRemoteDataSource {

    private val posts = FirebaseFirestore.getInstance().collection(AppConst.postsCollection)
    private val users = FirebaseFirestore.getInstance().collection(AppConst.usersCollection)
    private val comments = FirebaseFirestore.getInstance().collection(AppConst.commentsCollection)
    private val followers = FirebaseFirestore.getInstance().collection(AppConst.followersCollection)
    private val following = FirebaseFirestore.getInstance().collection(AppConst.followingCollection)
    private val feed = FirebaseFirestore.getInstance().collection(AppConst.feedCollection)

    /**
     * User
     */
    override suspend fun getSingleUser(uid: String): Resource<User> = withContext(Dispatchers.IO) {
        safeCall {
            val user = users.document(uid).get().await().toObject(User::class.java)
                    ?: throw IllegalStateException()

            Resource.Success(user)
        }
    }

    override suspend fun searchUsers(query: String): Resource<List<User>> =
            withContext(Dispatchers.IO) {
                safeCall {

                    val userResults = users
                            .whereGreaterThanOrEqualTo("username", query.toLowerCase(Locale.getDefault()))
                            .get()
                            .await()
                            .toObjects(User::class.java)

                    Resource.Success(userResults)
                }
            }

    override suspend fun toggleFollowForUser(uid: String): Resource<FollowResponseBody> =
            withContext(Dispatchers.IO) {
                safeCall {
                    // TODO: Elaborate on the usage and update variables names to provide better context
                    val currentUserId: String = auth.uid!!
                    var isFollowing: Boolean?

                    /**
                     * followers collection -> other user -> userFollowers -> current user
                     */
                    val followersRef =
                            followers.document(uid).collection(AppConst.userFollowersCollection)
                                    .document(currentUserId)

                    /**
                     * following collection -> current user -> user following -> other user
                     */
                    val followingRef =
                            following.document(currentUserId).collection(AppConst.userFollowingCollection)
                                    .document(uid)

                    val userFollowers = followersRef.get().await()
                    // remove follower
                    if (userFollowers.exists()) userFollowers.reference.delete().await()
                            .also { isFollowing = false }
                    // Make auth user follower of THAT user (update THEIR followers collection)
                    else followersRef.set(emptyMap<Any, Any>()).await().also { isFollowing = true }

                    val userFollowing = followingRef.get().await()
                    // remove following
                    if (userFollowing.exists()) userFollowing.reference.delete().await()
                            .also { isFollowing = false }
                    // Put THAT user on YOUR following collection (update your following collection)
                    else followingRef.set(emptyMap<Any, Any>()).await().also { isFollowing = true }

                    val followResponse = FollowResponseBody(uid, isFollowing)

                    Resource.Success(followResponse)
                }
            }

    override suspend fun checkIfFollowing(uid: String): Resource<FollowResponseBody> =
            withContext(Dispatchers.IO) {
                safeCall {
                    val currentUserId: String = auth.uid!!

                    var isFollowing: Boolean = false

                    val followersRef =
                            followers.document(uid).collection(AppConst.userFollowersCollection)
                                    .document(currentUserId).get().await()

                    if (followersRef.exists()) isFollowing = true

                    val followResponse = FollowResponseBody(uid, isFollowing)

                    Resource.Success(followResponse)
                }
            }

    override suspend fun getFollowingCount(uid: String): Resource<Int> = withContext(Dispatchers.IO) {
        safeCall {
            val result: Int = following
                    .document(uid)
                    .collection(AppConst.userFollowingCollection)
                    .get()
                    .await()
                    .documents
                    .size

            Resource.Success(result)
        }
    }

    override suspend fun getFollowersCount(uid: String): Resource<Int> = withContext(Dispatchers.IO) {
        safeCall {
            val result: Int = followers
                    .document(uid)
                    .collection(AppConst.userFollowersCollection)
                    .get()
                    .await()
                    .documents
                    .size

            Resource.Success(result)
        }
    }

    override suspend fun getPostsCount(uid: String): Resource<Int> = withContext(Dispatchers.IO) {
        safeCall {
            val result: Int = posts
                    .document(uid)
                    .collection(AppConst.usersPostsCollection)
                    .get()
                    .await()
                    .documents
                    .size

            Resource.Success(result)
        }
    }

    /**
     * Settings
     */
    override suspend fun updateUserProfile(body: ProfileUpdateRequestBody): Resource<Any> =
            withContext(Dispatchers.IO) {
                safeCall {

                    val imageUrl = body.photoUrl?.let { uri ->
                        updateProfilePicture(
                                body.uidToUpdate,
                                uri
                        ).toString()
                    }

                    val map = mutableMapOf("username" to body.username, "bio" to body.bio, "location" to body.location)

                    imageUrl?.let { uri -> map["photoUrl"] = uri }

                    users.document(body.uidToUpdate).update(map.toMap()).await()

                    Resource.Success(Any())
                }
            }

    override suspend fun updateProfilePicture(uid: String, imageUri: Uri): Uri? =
            withContext(Dispatchers.IO) {

                val storageRef = storage.getReference(uid)
                val user = getSingleUser(uid).data as User

                if (user.photoUrl != AppConst.DEFAULT_PROFILE_PICTURE_URL)
                    storage.getReferenceFromUrl(user.photoUrl).delete().await()

                storageRef.putFile(imageUri)
                        .await()
                        .metadata
                        ?.reference
                        ?.downloadUrl
                        ?.await()
            }

    /**
     * Timeline
     */
    override suspend fun getTimeline(): Resource<List<Post>> = withContext(Dispatchers.IO) {
        safeCall {

            val currentUserId = auth.uid!!

            val listOfUserIds: List<String> =
                    following
                            .document(currentUserId)
                            .collection(AppConst.userFollowingCollection)
                            .limit(30) // TODO: Introduce pagination
                            .get()
                            .await()
                            .documents
                            .map { it.id }

            val posts: List<Post> = listOfUserIds.flatMap { uid ->
                posts
                        .document(uid)
                        .collection(AppConst.usersPostsCollection)
                        .orderBy("timestamp", Query.Direction.DESCENDING)
                        .get()
                        .await()
                        .toObjects(Post::class.java)
                        .onEach { post ->
                            val user = getSingleUser(post.ownerId).data!!
                            post.authorUsername = user.username
                            post.authorProfilePictureUrl = user.photoUrl
                            post.isLiked = currentUserId in post.likedBy
                        }
            }

            Resource.Success(posts)
        }
    }

    /**
     * Posts
     */
    override suspend fun getPostsForProfile(uid: String) = withContext(Dispatchers.IO) {
        safeCall {
            val profilePosts = posts.whereEqualTo("ownerId", uid)
                    .orderBy("timestamp", Query.Direction.DESCENDING)
                    .get()
                    .await()
                    .toObjects(Post::class.java)
                    .onEach { post ->
                        val user: User = getSingleUser(post.ownerId).data!!
                        post.authorProfilePictureUrl = user.photoUrl
                        post.authorUsername = user.username
                    }

            Resource.Success(profilePosts)
        }
    }

    override suspend fun createPost(body: PostRequestBody): Resource<Any> =
            withContext(Dispatchers.IO) {
                safeCall {
                    val uid = auth.uid!!
                    val postId = UUID.randomUUID().toString()
                    val imageUploadResult = storage.getReference(postId).putFile(body.imageUri).await()
                    val imageUrl =
                            imageUploadResult?.metadata?.reference?.downloadUrl?.await().toString()

                    val post = Post(
                            id = postId,
                            ownerId = uid,
                            caption = body.caption,
                            imageUrl = imageUrl,
                            timestamp = System.currentTimeMillis()
                    )

                    posts.document(uid).collection(AppConst.usersPostsCollection).document(postId)
                            .set(post).await()

                    Resource.Success(Any())
                }
            }

    override suspend fun updatePost(body: PostToUpdateBody): Resource<Any> =
            withContext(Dispatchers.IO) {
                safeCall {
                    val uid = auth.uid!!
                    val map = mutableMapOf("caption" to body.caption)

                    posts
                            .document(uid)
                            .collection(AppConst.usersPostsCollection)
                            .document(body.postIdToUpdate)
                            .update(map.toMap())
                            .await()

                    Resource.Success(Any())
                }
            }

    override suspend fun deletePost(post: Post): Resource<Post> = withContext(Dispatchers.IO) {
        safeCall {
            val uid = auth.uid!!
            posts.document(uid).collection(AppConst.usersPostsCollection).document(post.id).delete()
                    .await()
            storage.getReferenceFromUrl(post.imageUrl).delete().await()
            Resource.Success(post)
        }
    }

    override suspend fun toggleLikeForPost(post: Post): Resource<Boolean> =
            withContext(Dispatchers.IO) {
                safeCall {

                    var isLiked = false

                    FirebaseFirestore.getInstance().runTransaction { transaction ->
                        val uid = auth.uid!!
                        val postResult = transaction.get(
                                posts.document(post.ownerId).collection(AppConst.usersPostsCollection)
                                        .document(post.id)
                        )
                        val currentLikes = postResult.toObject(Post::class.java)?.likedBy
                                ?: emptyList()

                        transaction.update(
                                posts.document(post.ownerId).collection(AppConst.usersPostsCollection)
                                        .document(post.id), "likedBy",
                                if (uid in currentLikes) {
                                    currentLikes - uid
                                } else {
                                    isLiked = true
                                    currentLikes + uid
                                }
                        )
                    }.await()

                    Resource.Success(isLiked)
                }
            }

    /**
     * Comments
     */
    override suspend fun getCommentsForPost(postId: String): Resource<List<Comment>> =
            withContext(Dispatchers.IO) {
                safeCall {

                    val commentsForPost = comments
                            .document(postId)
                            .collection(AppConst.postCommentsCollection)
                            .orderBy("timestamp", Query.Direction.DESCENDING)
                            .get()
                            .await()
                            .toObjects(Comment::class.java)
                            .onEach { comment ->
                                val user = getSingleUser(comment.userId).data!!

                                comment.authorUsername = user.username
                                comment.authorProfilePictureUrl = user.photoUrl
                            }

                    Resource.Success(commentsForPost)
                }
            }

    override suspend fun createComment(postId: String, comment: String): Resource<Comment> =
            withContext(Dispatchers.IO) {
                safeCall {

                    val uid = auth.uid!!
                    val user = getSingleUser(uid).data!!
                    val commentId = UUID.randomUUID().toString()

                    val commentRequestBody = Comment(
                            id = commentId,
                            userId = uid,
                            postId = postId,
                            comment = comment,
                            authorUsername = user.username,
                            authorProfilePictureUrl = user.photoUrl
                    )

                    comments.document(postId).collection(AppConst.postCommentsCollection)
                            .document(commentId).set(commentRequestBody).await()

                    Resource.Success(commentRequestBody)
                }
            }

    override suspend fun deleteComment(comment: Comment): Resource<Comment> =
            withContext(Dispatchers.IO) {
                safeCall {

                    comments
                            .document(comment.postId)
                            .collection(AppConst.postCommentsCollection)
                            .document(comment.id)
                            .delete()
                            .await()

                    Resource.Success(comment)
                }
            }

    /**
     * Notification feed
     */
    override suspend fun addLikeToFeed(ownerId: String, postId: String, postImage: String): Resource<Any> = withContext(Dispatchers.IO) {
        safeCall {
            val currentUserId = auth.uid!!
            val currentUser = getSingleUser(currentUserId).data!!

            val likeFeed = LikeFeed(
                    postId,
                    currentUserId,
                    currentUser.username,
                    currentUser.photoUrl,
                    postImage)

            if (currentUserId != ownerId) {
                feed.document(ownerId)
                        .collection(AppConst.userFeedCollection)
                        .document(postId)
                        .set(likeFeed)
                        .await()
            }

            Resource.Success(Any())
        }
    }

    override suspend fun removeLikeFromFeed(ownerId: String, postId: String): Resource<Any> = withContext(Dispatchers.IO) {
        safeCall {

            val currentUserId = auth.uid!!

            if (currentUserId != ownerId) {

                val feedCollectionRef = feed.document(ownerId)
                        .collection(AppConst.userFeedCollection)
                        .document(postId)
                        .get()
                        .await()

                if (feedCollectionRef.exists())
                    feedCollectionRef.reference.delete().await()
            }

            Resource.Success(Any())
        }
    }

    override suspend fun addCommentToFeed(postId: String, commentId: String, ownerId: String, comment: String, postImage: String): Resource<Any> = withContext(Dispatchers.IO) {
        safeCall {

            val currentUserId = auth.uid!!
            val currentUser = getSingleUser(currentUserId).data!!

            val isNotPostOwner = ownerId != currentUserId

            val commentFeed = CommentFeed(
                    postId,
                    currentUserId,
                    currentUser.username,
                    currentUser.photoUrl,
                    postImage,
                    comment
            )

            if (isNotPostOwner) {
                feed.document(ownerId)
                        .collection(AppConst.userFeedCollection)
                        .document(commentId)
                        .set(commentFeed)
                        .await()
            }

            Resource.Success(Any())
        }
    }

    override suspend fun removeCommentFromFeed(ownerId: String, commentId: String): Resource<Any> = withContext(Dispatchers.IO) {
        safeCall {

            feed.document(ownerId)
                    .collection(AppConst.userFeedCollection)
                    .document(commentId)
                    .get()
                    .await()
                    .reference
                    .delete()

            Resource.Success(Any())
        }
    }

    override suspend fun addFollowToFeed(ownerId: String): Resource<Any> = withContext(Dispatchers.IO) {
        safeCall {

            val currentUserId = auth.uid!!
            val currentUser = getSingleUser(currentUserId).data!!

            val followFeed = FollowFeed(
                    ownerId,
                    currentUserId,
                    currentUser.username,
                    currentUser.photoUrl
            )

            feed.document(ownerId)
                    .collection(AppConst.userFeedCollection)
                    .document(currentUserId)
                    .set(followFeed)
                    .await()


            Resource.Success(Any())
        }
    }

    override suspend fun removeFollowFromFeed(ownerId: String): Resource<Any> = withContext(Dispatchers.IO) {
        safeCall {

            val currentUserId = auth.uid!!

            feed.document(ownerId)
                    .collection(AppConst.userFeedCollection)
                    .document(currentUserId)
                    .get()
                    .await()
                    .run {
                        if (this.exists())
                            this.reference.delete().await()
                    }

            Resource.Success(Any())
        }
    }

    override suspend fun getNotificationFeed(): Resource<List<ActivityFeedItem>> = withContext(Dispatchers.IO) {
        safeCall {

            val currentUserId = auth.uid!!

            val result: List<ActivityFeedItem> = feed.document(currentUserId).collection(AppConst.userFeedCollection).get().await()
                    .toObjects(ActivityFeedItem::class.java)
                    .onEach { feedItem ->
                        val user = getSingleUser(feedItem.userId).data!!
                        feedItem.username = user.username
                        feedItem.avatar = user.photoUrl
                    }.toList()

            Resource.Success(result)
        }
    }
}