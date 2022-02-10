package com.example.instagram_clone.navigation

import android.content.Intent
import android.graphics.PorterDuff
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.appcompat.widget.LinearLayoutCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.request.RequestOptions
import com.example.instagram_clone.LoginActivity
import com.example.instagram_clone.MainActivity
import com.example.instagram_clone.R
import com.example.instagram_clone.navigation.model.AlarmDTO
import com.example.instagram_clone.navigation.model.ContentDTO
import com.example.instagram_clone.navigation.model.FollowDTO
import com.example.instagram_clone.navigation.util.FcmPush
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.ktx.Firebase
import kotlinx.android.synthetic.main.activity_main.*
import kotlinx.android.synthetic.main.fragment_user.view.*


class UserFragment : Fragment(){

    var fragmentview: View? = null
    var firestore: FirebaseFirestore? = null
    var uid: String? = null
    var auth: FirebaseAuth? = null
    var currentUserUid: String? = null


    companion object{
        var PICK_PROFILE_FROM_ALBUM = 10
    }


    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {

        fragmentview = LayoutInflater.from(activity).inflate(R.layout.fragment_user, container, false)
        uid = arguments?.getString("destinationUid")
        firestore = FirebaseFirestore.getInstance()
        auth = FirebaseAuth.getInstance()

        currentUserUid = auth?.currentUser?.uid

        if (uid == currentUserUid){
            //My page
            fragmentview?.account_btn_follow_signout?.text = getString(R.string.signout)
            fragmentview?.account_btn_follow_signout?.setOnClickListener {
                activity?.finish()
                startActivity(Intent(activity, LoginActivity::class.java))
                auth?.signOut()
            }
        } else {
            //OtherUserPage
            fragmentview?.account_btn_follow_signout?.text = getString(R.string.follow)
            var mainActivity = (activity as MainActivity)
            mainActivity.toolbar_username?.text = arguments?.getString("userId")
            mainActivity.toolbar_btn_back?.setOnClickListener {
                mainActivity.bottom_navigation.selectedItemId = R.id.action_home
            }
            mainActivity.toolbar_title_image?.visibility = View.GONE
            mainActivity.toolbar_username?.visibility = View.VISIBLE
            mainActivity.toolbar_btn_back?.visibility = View.VISIBLE
            fragmentview?.account_btn_follow_signout?.setOnClickListener {
                requestFollow()
            }
        }

        fragmentview?.account_recyclerview?.adapter = UserFragmentRecyclerViewAdapter()
        fragmentview?.account_recyclerview?.layoutManager = GridLayoutManager(activity, 3)

        //profile click
        fragmentview?.account_iv_profile?.setOnClickListener {
            val photoPickerIntent = Intent(Intent.ACTION_PICK)
            photoPickerIntent.type = "image/*"
            activity?.startActivityForResult(photoPickerIntent, PICK_PROFILE_FROM_ALBUM)
        }

        getProfileImage()
        getFollowerAndFollowing()
        return fragmentview
    }

    fun getFollowerAndFollowing(){
        firestore?.collection("users")?.document(uid!!)?.addSnapshotListener{ documentSnapshot, _ ->
            if (documentSnapshot == null) return@addSnapshotListener

            val followDTO = documentSnapshot.toObject(FollowDTO::class.java)

            if(followDTO?.followingCount != null){
                fragmentview?.account_tv_following_count?.text = followDTO.followingCount.toString()
            }
            if (followDTO?.followerCount != null){
                fragmentview?.account_tv_follower_count?.text = followDTO.followerCount.toString()

                //팔로우 중
                if(followDTO?.followers?.containsKey(currentUserUid!!)){
                    fragmentview?.account_btn_follow_signout?.text = getString(R.string.follow_cancel)
                    fragmentview?.account_btn_follow_signout?.background?.setColorFilter(ContextCompat.getColor(requireActivity(), R.color.colorLightGray), PorterDuff.Mode.MULTIPLY)

                    //팔로우 x
                } else {
                    if(uid != currentUserUid){
                        fragmentview?.account_btn_follow_signout?.text = getString(R.string.follow)
                        fragmentview?.account_btn_follow_signout?.background?.colorFilter = null
                    }
                }
            }
        }
    }


    fun requestFollow(){
        //Save data to my account (누굴 팔로워하는)
        var tsDocFollowing = firestore?.collection("users")?.document(currentUserUid!!)
        firestore?.runTransaction { transition ->
            var followDTO = transition.get(tsDocFollowing!!).toObject(FollowDTO::class.java)
            if (followDTO == null){
                followDTO = FollowDTO()
                followDTO.followingCount = 1
                followDTO.followers[uid!!] = true

                transition.set(tsDocFollowing, followDTO)
                return@runTransaction
            }

            if (followDTO.followers.containsKey(uid)){
                //It remove following third person when a third person follow me
                followDTO.followingCount = followDTO.followingCount - 1
                followDTO.followers.remove(uid)
            } else {
                //It add follwing third person when a third person do not follow me
                followDTO.followingCount = followDTO.followingCount + 1
                followDTO.followers[uid!!] = true
            }
            transition.set(tsDocFollowing, followDTO)
            return@runTransaction
        }

        //Save data to third person (팔로잉할 상대방 계정에 접근)
        val tsDocFollower = firestore?.collection("users")?.document(uid!!)
        firestore?.runTransaction { transition ->
            //읽기
            var followDTO = transition.get(tsDocFollower!!).toObject(FollowDTO::class.java)
            if(followDTO == null){
                //없을 때 FollowDTO 생성
                followDTO = FollowDTO()
                //최초 값이라 1 넣어줌 ?? 이해 못함
                followDTO!!.followerCount = 1
                //상대방 계정에 나의 uid 넣어줌
                followDTO!!.followers[currentUserUid!!] = true
                followerAlarm(uid!!)
                transition.set(tsDocFollower, followDTO!!)
                return@runTransaction
            }

            if(followDTO!!.followers.containsKey(currentUserUid)){
                //It cancel my folloser when I follow a third person
                followDTO!!.followerCount = followDTO!!.followerCount - 1
                followDTO!!.followers.remove(currentUserUid)
            } else {
                //It add my follower when I don't follow a third person
                followDTO!!.followerCount = followDTO!!.followerCount + 1
                followDTO!!.followers[currentUserUid!!] = true
                followerAlarm(uid!!)
            }

            transition.set(tsDocFollower, followDTO!!)
            return@runTransaction
        }
    }

    fun followerAlarm(destinationUid: String){
        var alarmDTO = AlarmDTO()
        alarmDTO.destinationUid = destinationUid
        alarmDTO.userId = FirebaseAuth.getInstance().currentUser?.email
        alarmDTO.uid = FirebaseAuth.getInstance().currentUser?.uid
        alarmDTO.kind = 2
        alarmDTO.timestamp = System.currentTimeMillis()
        FirebaseFirestore.getInstance().collection("alarms").document().set(alarmDTO)

        var message = FirebaseAuth.getInstance().currentUser?.email + getString(R.string.alarm_follow)
        FcmPush.instance.sendMessage(destinationUid, "Instagram", message)
    }

    //get profile
    fun getProfileImage(){
        firestore?.collection("profileImages")?.document(uid!!)?.addSnapshotListener{ documentSnapshot, _ ->
            if(documentSnapshot == null) return@addSnapshotListener
            if(documentSnapshot.data != null){
                val url = documentSnapshot.data!!["image"]
                Glide.with(requireActivity()).load(url).apply(RequestOptions().circleCrop()).into(fragmentview?.account_iv_profile!!)
            }

        }
    }





    inner class UserFragmentRecyclerViewAdapter: RecyclerView.Adapter<RecyclerView.ViewHolder>(){
        var contentDTOs: ArrayList<ContentDTO> = arrayListOf()
        init{
            firestore?.collection("images")?.whereEqualTo("uid", uid)?.addSnapshotListener{ querySnapshot, _ ->

                //Sometimes, This code return null of querySnapshot when it signout
                if (querySnapshot == null) return@addSnapshotListener

                //Get data
                for (snapshot in querySnapshot.documents){
                    contentDTOs.add(snapshot.toObject(ContentDTO::class.java)!!)
                }
                fragmentview?.account_tv_post_count?.text = contentDTOs.size.toString()
                notifyDataSetChanged()
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            var width = resources.displayMetrics.widthPixels / 3

            var imageView = ImageView(parent.context)
            imageView.layoutParams = LinearLayoutCompat.LayoutParams(width, width)
            return CustomViewHolder(imageView)
        }

        inner class CustomViewHolder(var imageView: ImageView) : RecyclerView.ViewHolder(imageView){

        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            var imageView = (holder as CustomViewHolder).imageView
            Glide.with(holder.imageView.context).load(contentDTOs[position].imageUrl).apply(RequestOptions().centerCrop()).into(imageView)
        }

        override fun getItemCount(): Int {
            return contentDTOs.size
        }

    }

}