package com.rasp.app

// Сюда нужно вставить данные из Supabase: Project Settings -> API
object Config {
    const val SUPABASE_URL = "https://ВСТАВЬ-СЮДА.supabase.co"
    const val SUPABASE_KEY = "ВСТАВЬ-СЮДА-anon-public-key"

    // Ник превращается в служебный адрес вида ник@gmail.com (письма не отправляются).
    // Если регистрация пишет "invalid email", поменяй домен здесь.
    const val EMAIL_DOMAIN = "gmail.com"
}
