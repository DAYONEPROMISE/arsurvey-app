package com.example.arsurvey.capture.data;

import androidx.annotation.NonNull;
import androidx.room.DatabaseConfiguration;
import androidx.room.InvalidationTracker;
import androidx.room.RoomDatabase;
import androidx.room.RoomOpenHelper;
import androidx.room.migration.AutoMigrationSpec;
import androidx.room.migration.Migration;
import androidx.room.util.DBUtil;
import androidx.room.util.TableInfo;
import androidx.sqlite.db.SupportSQLiteDatabase;
import androidx.sqlite.db.SupportSQLiteOpenHelper;
import java.lang.Class;
import java.lang.Override;
import java.lang.String;
import java.lang.SuppressWarnings;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.processing.Generated;

@Generated("androidx.room.RoomProcessor")
@SuppressWarnings({"unchecked", "deprecation"})
public final class CaptureDatabase_Impl extends CaptureDatabase {
  private volatile CaptureDao _captureDao;

  @Override
  @NonNull
  protected SupportSQLiteOpenHelper createOpenHelper(@NonNull final DatabaseConfiguration config) {
    final SupportSQLiteOpenHelper.Callback _openCallback = new RoomOpenHelper(config, new RoomOpenHelper.Delegate(1) {
      @Override
      public void createAllTables(@NonNull final SupportSQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `captured_objects` (`id` TEXT NOT NULL, `createdAtEpochMs` INTEGER NOT NULL, PRIMARY KEY(`id`))");
        db.execSQL("CREATE TABLE IF NOT EXISTS `captured_photos` (`rowId` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `objectId` TEXT NOT NULL, `photoIndex` INTEGER NOT NULL, `imagePath` TEXT NOT NULL, `depthRawPath` TEXT, `capturedAtEpochMs` INTEGER NOT NULL, `frameTimestampNs` INTEGER, `trackingState` TEXT, `imageWidth` INTEGER, `imageHeight` INTEGER, `depthAvailable` INTEGER NOT NULL, `depthWidth` INTEGER, `depthHeight` INTEGER, `metadataJson` TEXT NOT NULL, FOREIGN KEY(`objectId`) REFERENCES `captured_objects`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )");
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_captured_photos_objectId` ON `captured_photos` (`objectId`)");
        db.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)");
        db.execSQL("INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, '5634491ff1ff49915bbbc43aa76dd318')");
      }

      @Override
      public void dropAllTables(@NonNull final SupportSQLiteDatabase db) {
        db.execSQL("DROP TABLE IF EXISTS `captured_objects`");
        db.execSQL("DROP TABLE IF EXISTS `captured_photos`");
        final List<? extends RoomDatabase.Callback> _callbacks = mCallbacks;
        if (_callbacks != null) {
          for (RoomDatabase.Callback _callback : _callbacks) {
            _callback.onDestructiveMigration(db);
          }
        }
      }

      @Override
      public void onCreate(@NonNull final SupportSQLiteDatabase db) {
        final List<? extends RoomDatabase.Callback> _callbacks = mCallbacks;
        if (_callbacks != null) {
          for (RoomDatabase.Callback _callback : _callbacks) {
            _callback.onCreate(db);
          }
        }
      }

      @Override
      public void onOpen(@NonNull final SupportSQLiteDatabase db) {
        mDatabase = db;
        db.execSQL("PRAGMA foreign_keys = ON");
        internalInitInvalidationTracker(db);
        final List<? extends RoomDatabase.Callback> _callbacks = mCallbacks;
        if (_callbacks != null) {
          for (RoomDatabase.Callback _callback : _callbacks) {
            _callback.onOpen(db);
          }
        }
      }

      @Override
      public void onPreMigrate(@NonNull final SupportSQLiteDatabase db) {
        DBUtil.dropFtsSyncTriggers(db);
      }

      @Override
      public void onPostMigrate(@NonNull final SupportSQLiteDatabase db) {
      }

      @Override
      @NonNull
      public RoomOpenHelper.ValidationResult onValidateSchema(
          @NonNull final SupportSQLiteDatabase db) {
        final HashMap<String, TableInfo.Column> _columnsCapturedObjects = new HashMap<String, TableInfo.Column>(2);
        _columnsCapturedObjects.put("id", new TableInfo.Column("id", "TEXT", true, 1, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsCapturedObjects.put("createdAtEpochMs", new TableInfo.Column("createdAtEpochMs", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        final HashSet<TableInfo.ForeignKey> _foreignKeysCapturedObjects = new HashSet<TableInfo.ForeignKey>(0);
        final HashSet<TableInfo.Index> _indicesCapturedObjects = new HashSet<TableInfo.Index>(0);
        final TableInfo _infoCapturedObjects = new TableInfo("captured_objects", _columnsCapturedObjects, _foreignKeysCapturedObjects, _indicesCapturedObjects);
        final TableInfo _existingCapturedObjects = TableInfo.read(db, "captured_objects");
        if (!_infoCapturedObjects.equals(_existingCapturedObjects)) {
          return new RoomOpenHelper.ValidationResult(false, "captured_objects(com.example.arsurvey.capture.data.CapturedObjectEntity).\n"
                  + " Expected:\n" + _infoCapturedObjects + "\n"
                  + " Found:\n" + _existingCapturedObjects);
        }
        final HashMap<String, TableInfo.Column> _columnsCapturedPhotos = new HashMap<String, TableInfo.Column>(14);
        _columnsCapturedPhotos.put("rowId", new TableInfo.Column("rowId", "INTEGER", true, 1, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsCapturedPhotos.put("objectId", new TableInfo.Column("objectId", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsCapturedPhotos.put("photoIndex", new TableInfo.Column("photoIndex", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsCapturedPhotos.put("imagePath", new TableInfo.Column("imagePath", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsCapturedPhotos.put("depthRawPath", new TableInfo.Column("depthRawPath", "TEXT", false, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsCapturedPhotos.put("capturedAtEpochMs", new TableInfo.Column("capturedAtEpochMs", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsCapturedPhotos.put("frameTimestampNs", new TableInfo.Column("frameTimestampNs", "INTEGER", false, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsCapturedPhotos.put("trackingState", new TableInfo.Column("trackingState", "TEXT", false, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsCapturedPhotos.put("imageWidth", new TableInfo.Column("imageWidth", "INTEGER", false, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsCapturedPhotos.put("imageHeight", new TableInfo.Column("imageHeight", "INTEGER", false, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsCapturedPhotos.put("depthAvailable", new TableInfo.Column("depthAvailable", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsCapturedPhotos.put("depthWidth", new TableInfo.Column("depthWidth", "INTEGER", false, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsCapturedPhotos.put("depthHeight", new TableInfo.Column("depthHeight", "INTEGER", false, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsCapturedPhotos.put("metadataJson", new TableInfo.Column("metadataJson", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        final HashSet<TableInfo.ForeignKey> _foreignKeysCapturedPhotos = new HashSet<TableInfo.ForeignKey>(1);
        _foreignKeysCapturedPhotos.add(new TableInfo.ForeignKey("captured_objects", "CASCADE", "NO ACTION", Arrays.asList("objectId"), Arrays.asList("id")));
        final HashSet<TableInfo.Index> _indicesCapturedPhotos = new HashSet<TableInfo.Index>(1);
        _indicesCapturedPhotos.add(new TableInfo.Index("index_captured_photos_objectId", false, Arrays.asList("objectId"), Arrays.asList("ASC")));
        final TableInfo _infoCapturedPhotos = new TableInfo("captured_photos", _columnsCapturedPhotos, _foreignKeysCapturedPhotos, _indicesCapturedPhotos);
        final TableInfo _existingCapturedPhotos = TableInfo.read(db, "captured_photos");
        if (!_infoCapturedPhotos.equals(_existingCapturedPhotos)) {
          return new RoomOpenHelper.ValidationResult(false, "captured_photos(com.example.arsurvey.capture.data.CapturedPhotoEntity).\n"
                  + " Expected:\n" + _infoCapturedPhotos + "\n"
                  + " Found:\n" + _existingCapturedPhotos);
        }
        return new RoomOpenHelper.ValidationResult(true, null);
      }
    }, "5634491ff1ff49915bbbc43aa76dd318", "a811f03ab3fa51dd15a1d5909d8ae91e");
    final SupportSQLiteOpenHelper.Configuration _sqliteConfig = SupportSQLiteOpenHelper.Configuration.builder(config.context).name(config.name).callback(_openCallback).build();
    final SupportSQLiteOpenHelper _helper = config.sqliteOpenHelperFactory.create(_sqliteConfig);
    return _helper;
  }

  @Override
  @NonNull
  protected InvalidationTracker createInvalidationTracker() {
    final HashMap<String, String> _shadowTablesMap = new HashMap<String, String>(0);
    final HashMap<String, Set<String>> _viewTables = new HashMap<String, Set<String>>(0);
    return new InvalidationTracker(this, _shadowTablesMap, _viewTables, "captured_objects","captured_photos");
  }

  @Override
  public void clearAllTables() {
    super.assertNotMainThread();
    final SupportSQLiteDatabase _db = super.getOpenHelper().getWritableDatabase();
    final boolean _supportsDeferForeignKeys = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP;
    try {
      if (!_supportsDeferForeignKeys) {
        _db.execSQL("PRAGMA foreign_keys = FALSE");
      }
      super.beginTransaction();
      if (_supportsDeferForeignKeys) {
        _db.execSQL("PRAGMA defer_foreign_keys = TRUE");
      }
      _db.execSQL("DELETE FROM `captured_objects`");
      _db.execSQL("DELETE FROM `captured_photos`");
      super.setTransactionSuccessful();
    } finally {
      super.endTransaction();
      if (!_supportsDeferForeignKeys) {
        _db.execSQL("PRAGMA foreign_keys = TRUE");
      }
      _db.query("PRAGMA wal_checkpoint(FULL)").close();
      if (!_db.inTransaction()) {
        _db.execSQL("VACUUM");
      }
    }
  }

  @Override
  @NonNull
  protected Map<Class<?>, List<Class<?>>> getRequiredTypeConverters() {
    final HashMap<Class<?>, List<Class<?>>> _typeConvertersMap = new HashMap<Class<?>, List<Class<?>>>();
    _typeConvertersMap.put(CaptureDao.class, CaptureDao_Impl.getRequiredConverters());
    return _typeConvertersMap;
  }

  @Override
  @NonNull
  public Set<Class<? extends AutoMigrationSpec>> getRequiredAutoMigrationSpecs() {
    final HashSet<Class<? extends AutoMigrationSpec>> _autoMigrationSpecsSet = new HashSet<Class<? extends AutoMigrationSpec>>();
    return _autoMigrationSpecsSet;
  }

  @Override
  @NonNull
  public List<Migration> getAutoMigrations(
      @NonNull final Map<Class<? extends AutoMigrationSpec>, AutoMigrationSpec> autoMigrationSpecs) {
    final List<Migration> _autoMigrations = new ArrayList<Migration>();
    return _autoMigrations;
  }

  @Override
  public CaptureDao captureDao() {
    if (_captureDao != null) {
      return _captureDao;
    } else {
      synchronized(this) {
        if(_captureDao == null) {
          _captureDao = new CaptureDao_Impl(this);
        }
        return _captureDao;
      }
    }
  }
}
