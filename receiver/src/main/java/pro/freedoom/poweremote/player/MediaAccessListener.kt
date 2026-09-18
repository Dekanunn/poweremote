package pro.freedoom.poweremote.player

import android.service.notification.NotificationListenerService

/**
 * Пустой сервис. Существует только для того, чтобы система выдала приложению
 * доступ к активным медиа-сессиям: MediaSessionManager.getActiveSessions()
 * требует ComponentName включённого NotificationListenerService.
 *
 * Уведомления мы не читаем и никуда не передаём.
 */
class MediaAccessListener : NotificationListenerService()
