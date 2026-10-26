package online.soymasdigital.jornada.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import online.soymasdigital.jornada.MainActivity
import online.soymasdigital.jornada.R

/** Servicio en primer plano con la notificacion persistente de la jornada (botones de hito). */
class JornadaService : Service() {

    companion object {
        const val CANAL = "jornada"
        const val ACCION_SNAPSHOT = "snapshot"
        const val ACCION_INCIDENCIA = "incidencia"
        const val ACCION_KM = "km"
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val canal = NotificationChannel(CANAL, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW)
        canal.description = "Avisos de hitos de la jornada (cartera, cliente ausente)"
        nm.createNotificationChannel(canal)
    }

    private fun pi(requestCode: Int, accion: String?): PendingIntent {
        val i = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
        if (accion != null) i.putExtra("accion", accion)
        return PendingIntent.getActivity(this, requestCode, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val n: Notification = NotificationCompat.Builder(this, CANAL)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("Jornada en curso")
            .setContentText("Registra KM de la moto y CARTERA (inicio y fin). Sin los cuatro datos no se puede cerrar la jornada.")
            .setStyle(NotificationCompat.BigTextStyle().bigText(
                "Cierre duro: hacen falta los km de moto de inicio y de fin, y la cartera de inicio y de fin. " +
                    "Si llueve o hay clima adverso, pulsa LLUVIA en la app para justificar retrasos. " +
                    "Las incidencias de cliente ausente se registran en el momento."))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pi(0, null))
            .addAction(0, "KM MOTO", pi(1, ACCION_KM))
            .addAction(0, "CARTERA", pi(2, ACCION_SNAPSHOT))
            .addAction(0, "CLIENTE AUSENTE", pi(3, ACCION_INCIDENCIA))
            .build()
        startForeground(1, n)
        return START_STICKY
    }
}
