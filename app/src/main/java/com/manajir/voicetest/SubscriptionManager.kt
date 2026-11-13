package com.manajir.voicetest

import android.content.Context
import com.google.firebase.firestore.FirebaseFirestore

object SubscriptionManager {
    private val db = FirebaseFirestore.getInstance()

    fun submitPaymentRequest(email: String, planName: String, amount: String, trxId: String, onResult: (Boolean, String) -> Unit) {
        val requestMap = hashMapOf(
            "email" to email,
            "plan" to planName,
            "amount" to amount,
            "trxId" to trxId,
            "status" to "pending",
            "timestamp" to System.currentTimeMillis()
        )

        db.collection("payment_requests").document(email)
            .set(requestMap)
            .addOnSuccessListener { onResult(true, "রিকোয়েস্ট সফলভাবে জমা হয়েছে। অ্যাডমিন যাচাই করার পর Key দেওয়া হবে।") }
            .addOnFailureListener { e -> onResult(false, e.message ?: "ত্রুটি ঘটেছে") }
    }

    fun verifyAndActivateKey(context: Context, email: String, enteredKey: String, onResult: (Boolean, String) -> Unit) {
        db.collection("subscription_keys").document(enteredKey)
            .get()
            .addOnSuccessListener { document ->
                if (document.exists()) {
                    val boundEmail = document.getString("email")
                    val isUsed = document.getBoolean("isUsed") ?: false

                    if (boundEmail != null && boundEmail != email) {
                        onResult(false, "এই Key অন্য ইমেইলের জন্য রেজিস্টার্ড!")
                        return@addOnSuccessListener
                    }

                    if (isUsed && boundEmail == email) {
                        val prefs = context.getSharedPreferences("app_sub", Context.MODE_PRIVATE)
                        prefs.edit().putBoolean("is_subscribed", true).apply()
                        onResult(true, "সাবস্ক্রিপশন সক্রিয় রয়েছে!")
                        return@addOnSuccessListener
                    }

                    if (!isUsed) {
                        val updateMap = mapOf(
                            "isUsed" to true,
                            "email" to email,
                            "activatedAt" to System.currentTimeMillis()
                        )
                        db.collection("subscription_keys").document(enteredKey)
                            .update(updateMap)
                            .addOnSuccessListener {
                                val prefs = context.getSharedPreferences("app_sub", Context.MODE_PRIVATE)
                                prefs.edit().putBoolean("is_subscribed", true).apply()
                                onResult(true, "সাবস্ক্রিপশন সফলভাবে সক্রিয় হয়েছে!")
                            }
                            .addOnFailureListener {
                                onResult(false, "অ্যাক্টিভেশন ব্যর্থ হয়েছে।")
                            }
                    }
                } else {
                    onResult(false, "ভুল অথবা অকার্যকর Key!")
                }
            }
            .addOnFailureListener {
                onResult(false, "নেটওয়ার্ক সমস্যা!")
            }
    }

    fun isSubscribed(context: Context): Boolean {
        val prefs = context.getSharedPreferences("app_sub", Context.MODE_PRIVATE)
        return prefs.getBoolean("is_subscribed", false)
    }
}
