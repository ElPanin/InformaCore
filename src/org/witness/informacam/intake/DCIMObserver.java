package org.witness.informacam.intake;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.List;
import java.util.Vector;

import org.witness.informacam.InformaCam;
import org.witness.informacam.models.j3m.IDCIMDescriptor;
import org.witness.informacam.utils.Constants.Logger;
import org.witness.informacam.utils.Constants.App.Storage;

import android.app.ActivityManager;
import android.app.ActivityManager.RunningAppProcessInfo;
import android.content.ComponentName;
import android.content.Context;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.FileObserver;
import android.os.Handler;
import android.provider.MediaStore;
import android.util.Log;

public class DCIMObserver {
	private final static String LOG = Storage.LOG;

	public IDCIMDescriptor dcimDescriptor;
	List<ContentObserver> observers;
	InformaCam informaCam = InformaCam.getInstance();

	Handler h;
	private Context mContext;

	private FileMonitor fileMonitor;
	private int raPID = -1;

	private boolean debug = false;

	public DCIMObserver(Context context, String parentId, ComponentName cameraComponent) {

		mContext = context;

		List<RunningAppProcessInfo> running = ((ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE)).getRunningAppProcesses();
		for(RunningAppProcessInfo r : running) {
			if(r.processName.equals(cameraComponent.getPackageName())) {
				raPID = r.pid;
				break;
			}
		}

		h = new Handler();

		fileMonitor = new FileMonitor();

		observers = new Vector<ContentObserver>();
		observers.add(new Observer(h, MediaStore.Images.Media.EXTERNAL_CONTENT_URI));
		observers.add(new Observer(h, MediaStore.Images.Media.INTERNAL_CONTENT_URI));
		observers.add(new Observer(h, MediaStore.Video.Media.EXTERNAL_CONTENT_URI));
		observers.add(new Observer(h, MediaStore.Video.Media.INTERNAL_CONTENT_URI));
		observers.add(new Observer(h, MediaStore.Images.Thumbnails.EXTERNAL_CONTENT_URI));
		observers.add(new Observer(h, MediaStore.Images.Thumbnails.INTERNAL_CONTENT_URI));
		observers.add(new Observer(h, MediaStore.Video.Thumbnails.EXTERNAL_CONTENT_URI));
		observers.add(new Observer(h, MediaStore.Video.Thumbnails.INTERNAL_CONTENT_URI));

		for(ContentObserver o : observers) {
			mContext.getContentResolver().registerContentObserver(((Observer) o).authority, false, o);
		}

		dcimDescriptor = new IDCIMDescriptor(parentId);
		fileMonitor.start();
		dcimDescriptor.startSession();

		Log.d(LOG, "DCIM OBSERVER INITED");
	}

	public void destroy() {
		dcimDescriptor.stopSession();


		for(ContentObserver o : observers) {
			mContext.getContentResolver().unregisterContentObserver(o);
		}

		fileMonitor.stop();
		Log.d(LOG, "DCIM OBSERVER STOPPED");

	}

	class FileMonitor extends FileObserver {
		public FileMonitor() {
			super(Storage.DCIM);
			Log.d(LOG, "STARTING FILE OBSERVER ON PATH: " + Storage.DCIM);
		}

		public void start() {
			startWatching();
		}

		public void stop() {			
			stopWatching();
		}

		private void lsof() {
			lsof(true, null);
		}

		private void lsof(boolean mask, String fileToWatch) {
			String line;
			Process check;
			try {
				check = Runtime.getRuntime().exec(String.format("lsof -r1 %s/*", Storage.DCIM));
				BufferedReader br = new BufferedReader(new InputStreamReader(check.getInputStream()));
				while((line = br.readLine()) != null) {
					if(fileToWatch != null) {
						if(!line.contains(fileToWatch)) {
							continue;
						}
					}

					if(!mask || line.contains(String.valueOf(raPID))) {
						Log.d(LOG, line);
					}
				}
			} catch (IOException e) {
				Logger.e(LOG, e);
			}
		}

		private void ls(String fileToWatch) {
			String line;
			Process check;
			try {
				check = Runtime.getRuntime().exec(String.format("ls -la %s", fileToWatch));
				BufferedReader br = new BufferedReader(new InputStreamReader(check.getInputStream()));
				while((line = br.readLine()) != null) {
					Log.d(LOG, line);
				}
			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}

		@Override
		public void onEvent(int event, String path) {
			path = (Storage.DCIM + "/" + path);

			if(debug) {
				switch (event) {
				case  FileObserver.ACCESS:
					Log.d(LOG, "FILE OBSERVER: ACCESS");
					break;
				case FileObserver.MODIFY:
					Log.d(LOG, "FILE OBSERVER: MODIFY");
					break;
				case FileObserver.ATTRIB:
					Log.d(LOG, "FILE OBSERVER: ATTRIB");
					ls(path);
					break;
				case FileObserver.CLOSE_WRITE:
					Log.d(LOG, "FILE OBSERVER: CLOSE_WRITE");
					ls(path);
					break;
				case FileObserver.CLOSE_NOWRITE:
					Log.d(LOG, "FILE OBSERVER: CLOSE_NOWRITE");
					break;
				case FileObserver.OPEN:
					Log.d(LOG, "FILE OBSERVER: OPEN");
					lsof(false, path);
					break;
				case FileObserver.MOVED_FROM:
					Log.d(LOG, "FILE OBSERVER: MOVED_FROM");
					break;
				case FileObserver.MOVED_TO:
					Log.d(LOG, "FILE OBSERVER: MOVED_TO");
					break;
				case FileObserver.CREATE:
					Log.d(LOG, "FILE OBSERVER: CREATE");
					// WHICH PROCESS THOUGH???!!!
					lsof(false, null);
					ls(path);

					break;
				case FileObserver.DELETE:
					Log.d(LOG, "FILE OBSERVER: DELETE");
					break;
				case FileObserver.DELETE_SELF:
					Log.d(LOG, "FILE OBSERVER: DELETE_SELF");
					break;
				case FileObserver.MOVE_SELF:
					Log.d(LOG, "FILE OBSERVER: MOVE_SELF");
					break;
				default:
					Log.d(LOG, "FILE OBSERVER: UNKNOWN");
					lsof(false, path);
					break;

				}


				Log.d(LOG, "THE FILE OBSERVER SAW EVT: " + event + " on path " + path);
			}

		}
	}

	class Observer extends ContentObserver {
		Uri authority;

		public Observer(Handler handler, Uri authority) {
			super(handler);
			this.authority = authority;
		}

		@Override
		public void onChange(boolean selfChange) {
			Log.d(LOG, "ON CHANGE CALLED (no URI)");
			onChange(selfChange, null);

		}

		@Override
		public void onChange(boolean selfChange, Uri uri) {
			// we don't need the coords now, but let's ping the informa service to update its location
			informaCam.informaService.getCurrentLocation();
			
			if(uri != null) {
				Log.d(LOG, "ON CHANGE CALLED (with URI!)");
			}

			boolean isThumbnail = false;

			if(
					authority.equals(MediaStore.Images.Thumbnails.EXTERNAL_CONTENT_URI) || 
					authority.equals(MediaStore.Images.Thumbnails.INTERNAL_CONTENT_URI) ||
					authority.equals(MediaStore.Video.Thumbnails.EXTERNAL_CONTENT_URI) || 
					authority.equals(MediaStore.Video.Thumbnails.INTERNAL_CONTENT_URI) 
					) {
				isThumbnail = true;
			}

			dcimDescriptor.addEntry(authority, isThumbnail);
		}

		@Override
		public boolean deliverSelfNotifications() {
			return true;
		}

	}

}
