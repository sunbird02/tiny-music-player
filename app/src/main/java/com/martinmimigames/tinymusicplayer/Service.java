package com.martinmimigames.tinymusicplayer;

import android.annotation.TargetApi;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;

import java.io.IOException;

/**
 * service for playing music
 */
public class Service extends android.app.Service {

  final HWListener hwListener;
  final Notifications notifications;
  /**
   * audio playing logic class
   */
  private AudioPlayer audioPlayer;
  /**
   * playlist and current position
   */
  private java.util.ArrayList<android.net.Uri> playlist;
  private int currentTrackIndex;
  private boolean isLooping;

  public Service() {
    hwListener = new HWListener(this);
    notifications = new Notifications(this);
  }

  /**
   * unused
   */
  @Override
  public IBinder onBind(Intent intent) {
    return null;
  }

  /**
   * setup
   */
  @Override
  public void onCreate() {
    hwListener.create();
    notifications.create();

    super.onCreate();
  }

  /**
   * startup logic
   */
  @Override
  public void onStart(final Intent intent, final int startId) {
    /* check if called from self */
    if (intent.getAction() == null) {
      var isPLaying = audioPlayer != null && audioPlayer.isPlaying();
      var isLooping = this.isLooping;
      switch (intent.getByteExtra(Launcher.TYPE, Launcher.NULL)) {
        /* start or pause audio playback */
        case Launcher.PLAY_PAUSE -> setState(!isPLaying, isLooping);
        case Launcher.PLAY -> setState(true, isLooping);
        case Launcher.PAUSE -> setState(false, isLooping);
        case Launcher.LOOP -> setState(isPLaying, !isLooping);
        /* cancel audio playback and kill service */
        case Launcher.KILL -> stopSelf();
      }
    } else {
      switch (intent.getAction()) {
        case Intent.ACTION_VIEW -> setAudio(intent.getData(), intent.getClipData());
        case Intent.ACTION_SEND -> setAudio(intent.getParcelableExtra(Intent.EXTRA_STREAM), null);
      }
    }
  }

  void setAudio(final Uri audioLocation, final android.content.ClipData clipData) {
    try {
      /* build playlist */
      playlist = new java.util.ArrayList<>();
      currentTrackIndex = 0;
      isLooping = true; // default to loop playlist
      
      if (clipData != null && clipData.getItemCount() > 0) {
        /* multiple files selected */
        for (int i = 0; i < clipData.getItemCount(); i++) {
          playlist.add(clipData.getItemAt(i).getUri());
        }
      } else if (audioLocation != null) {
        /* single file */
        playlist.add(audioLocation);
      } else {
        return;
      }
      
      /* get audio playback logic and start async */
      playCurrentTrack();

    } catch (IllegalArgumentException e) {
      Exceptions.throwError(this, Exceptions.IllegalArgument);
    } catch (SecurityException e) {
      Exceptions.throwError(this, Exceptions.Security);
    } catch (IllegalStateException e) {
      Exceptions.throwError(this, Exceptions.IllegalState);
    }
  }
  
  void playCurrentTrack() {
    if (playlist.isEmpty() || currentTrackIndex >= playlist.size()) {
      stopSelf();
      return;
    }
    
    try {
      Uri currentUri = playlist.get(currentTrackIndex);
      audioPlayer = new AudioPlayer(this, currentUri);
      audioPlayer.start();
      
      /* create notification for playback control */
      notifications.getNotification(currentUri);
      
      /* start service as foreground */
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.ECLAIR)
        startForeground(Notifications.NOTIFICATION_ID, notifications.notification);
        
    } catch (IllegalArgumentException | SecurityException | IllegalStateException e) {
      /* skip to next track on error */
      playNextTrack();
    } catch (IOException e) {
      Exceptions.throwError(this, Exceptions.IO);
    }
  }
  
  void playNextTrack() {
    if (playlist.isEmpty()) return;
    
    currentTrackIndex = (currentTrackIndex + 1) % playlist.size();
    
    if (audioPlayer != null && !audioPlayer.isInterrupted()) {
      audioPlayer.interrupt();
    }
    
    playCurrentTrack();
  }

  /**
   * Switch to player component state
   */
  void setState(boolean playing, boolean looping) {
    this.isLooping = looping;
    audioPlayer.setState(playing, looping);
    hwListener.setState(playing, looping);
    notifications.setState(playing, looping);
  }

  /**
   * forward to startup logic for newer androids
   */
  @TargetApi(Build.VERSION_CODES.ECLAIR)
  @Override
  public int onStartCommand(final Intent intent, final int flags, final int startId) {
    onStart(intent, startId);
    return START_STICKY;
  }

  /**
   * service killing logic
   */
  @Override
  public void onDestroy() {
    notifications.destroy();
    hwListener.destroy();
    /* interrupt audio playback logic */
    if (!audioPlayer.isInterrupted()) audioPlayer.interrupt();

    super.onDestroy();
  }
}
