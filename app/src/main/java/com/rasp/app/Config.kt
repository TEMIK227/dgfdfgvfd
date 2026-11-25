package com.rasp.app

// Сюда нужно вставить данные из Supabase: Project Settings -> API
object Config {
    const val SUPABASE_URL = "https://qzdxbzxceghsjlrugwcf.supabase.coy"
    const val SUPABASE_KEY = "sb_publishable_tf7OMTI8vSJj4gsUdvW3dQ_iL4nigRv"

    // Ник превращается в служебный адрес вида ник@gmail.com (письма не отправляются).
    // Если регистрация пишет "invalid email", поменяй домен здесь.
    const val EMAIL_DOMAIN = "gmail.com"
}
