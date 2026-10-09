package com.example.arsurvey.capture.data;

import android.database.Cursor;
import android.os.CancellationSignal;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.collection.ArrayMap;
import androidx.room.CoroutinesRoom;
import androidx.room.EntityInsertionAdapter;
import androidx.room.RoomDatabase;
import androidx.room.RoomSQLiteQuery;
import androidx.room.SharedSQLiteStatement;
import androidx.room.util.CursorUtil;
import androidx.room.util.DBUtil;
import androidx.room.util.RelationUtil;
import androidx.room.util.StringUtil;
import androidx.sqlite.db.SupportSQLiteStatement;
import java.lang.Class;
import java.lang.Exception;
import java.lang.Integer;
import java.lang.Long;
import java.lang.Object;
import java.lang.Override;
import java.lang.String;
import java.lang.StringBuilder;
import java.lang.SuppressWarnings;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import javax.annotation.processing.Generated;
import kotlin.Unit;
import kotlin.coroutines.Continuation;
import kotlinx.coroutines.flow.Flow;

@Generated("androidx.room.RoomProcessor")
@SuppressWarnings({"unchecked", "deprecation"})
public final class CaptureDao_Impl implements CaptureDao {
  private final RoomDatabase __db;

  private final EntityInsertionAdapter<CapturedObjectEntity> __insertionAdapterOfCapturedObjectEntity;

  private final EntityInsertionAdapter<CapturedPhotoEntity> __insertionAdapterOfCapturedPhotoEntity;

  private final SharedSQLiteStatement __preparedStmtOfDeletePhoto;

  private final SharedSQLiteStatement __preparedStmtOfDeletePhotosOf;

  private final SharedSQLiteStatement __preparedStmtOfDeleteObject;

  public CaptureDao_Impl(@NonNull final RoomDatabase __db) {
    this.__db = __db;
    this.__insertionAdapterOfCapturedObjectEntity = new EntityInsertionAdapter<CapturedObjectEntity>(__db) {
      @Override
      @NonNull
      protected String createQuery() {
        return "INSERT OR REPLACE INTO `captured_objects` (`id`,`createdAtEpochMs`) VALUES (?,?)";
      }

      @Override
      protected void bind(@NonNull final SupportSQLiteStatement statement,
          @NonNull final CapturedObjectEntity entity) {
        statement.bindString(1, entity.getId());
        statement.bindLong(2, entity.getCreatedAtEpochMs());
      }
    };
    this.__insertionAdapterOfCapturedPhotoEntity = new EntityInsertionAdapter<CapturedPhotoEntity>(__db) {
      @Override
      @NonNull
      protected String createQuery() {
        return "INSERT OR REPLACE INTO `captured_photos` (`rowId`,`objectId`,`photoIndex`,`imagePath`,`depthRawPath`,`capturedAtEpochMs`,`frameTimestampNs`,`trackingState`,`imageWidth`,`imageHeight`,`depthAvailable`,`depthWidth`,`depthHeight`,`metadataJson`) VALUES (nullif(?, 0),?,?,?,?,?,?,?,?,?,?,?,?,?)";
      }

      @Override
      protected void bind(@NonNull final SupportSQLiteStatement statement,
          @NonNull final CapturedPhotoEntity entity) {
        statement.bindLong(1, entity.getRowId());
        statement.bindString(2, entity.getObjectId());
        statement.bindLong(3, entity.getPhotoIndex());
        statement.bindString(4, entity.getImagePath());
        if (entity.getDepthRawPath() == null) {
          statement.bindNull(5);
        } else {
          statement.bindString(5, entity.getDepthRawPath());
        }
        statement.bindLong(6, entity.getCapturedAtEpochMs());
        if (entity.getFrameTimestampNs() == null) {
          statement.bindNull(7);
        } else {
          statement.bindLong(7, entity.getFrameTimestampNs());
        }
        if (entity.getTrackingState() == null) {
          statement.bindNull(8);
        } else {
          statement.bindString(8, entity.getTrackingState());
        }
        if (entity.getImageWidth() == null) {
          statement.bindNull(9);
        } else {
          statement.bindLong(9, entity.getImageWidth());
        }
        if (entity.getImageHeight() == null) {
          statement.bindNull(10);
        } else {
          statement.bindLong(10, entity.getImageHeight());
        }
        final int _tmp = entity.getDepthAvailable() ? 1 : 0;
        statement.bindLong(11, _tmp);
        if (entity.getDepthWidth() == null) {
          statement.bindNull(12);
        } else {
          statement.bindLong(12, entity.getDepthWidth());
        }
        if (entity.getDepthHeight() == null) {
          statement.bindNull(13);
        } else {
          statement.bindLong(13, entity.getDepthHeight());
        }
        statement.bindString(14, entity.getMetadataJson());
      }
    };
    this.__preparedStmtOfDeletePhoto = new SharedSQLiteStatement(__db) {
      @Override
      @NonNull
      public String createQuery() {
        final String _query = "DELETE FROM captured_photos WHERE objectId = ? AND photoIndex = ?";
        return _query;
      }
    };
    this.__preparedStmtOfDeletePhotosOf = new SharedSQLiteStatement(__db) {
      @Override
      @NonNull
      public String createQuery() {
        final String _query = "DELETE FROM captured_photos WHERE objectId = ?";
        return _query;
      }
    };
    this.__preparedStmtOfDeleteObject = new SharedSQLiteStatement(__db) {
      @Override
      @NonNull
      public String createQuery() {
        final String _query = "DELETE FROM captured_objects WHERE id = ?";
        return _query;
      }
    };
  }

  @Override
  public Object insertObject(final CapturedObjectEntity obj,
      final Continuation<? super Unit> $completion) {
    return CoroutinesRoom.execute(__db, true, new Callable<Unit>() {
      @Override
      @NonNull
      public Unit call() throws Exception {
        __db.beginTransaction();
        try {
          __insertionAdapterOfCapturedObjectEntity.insert(obj);
          __db.setTransactionSuccessful();
          return Unit.INSTANCE;
        } finally {
          __db.endTransaction();
        }
      }
    }, $completion);
  }

  @Override
  public Object insertPhoto(final CapturedPhotoEntity photo,
      final Continuation<? super Unit> $completion) {
    return CoroutinesRoom.execute(__db, true, new Callable<Unit>() {
      @Override
      @NonNull
      public Unit call() throws Exception {
        __db.beginTransaction();
        try {
          __insertionAdapterOfCapturedPhotoEntity.insert(photo);
          __db.setTransactionSuccessful();
          return Unit.INSTANCE;
        } finally {
          __db.endTransaction();
        }
      }
    }, $completion);
  }

  @Override
  public Object deletePhoto(final String objectId, final int index,
      final Continuation<? super Unit> $completion) {
    return CoroutinesRoom.execute(__db, true, new Callable<Unit>() {
      @Override
      @NonNull
      public Unit call() throws Exception {
        final SupportSQLiteStatement _stmt = __preparedStmtOfDeletePhoto.acquire();
        int _argIndex = 1;
        _stmt.bindString(_argIndex, objectId);
        _argIndex = 2;
        _stmt.bindLong(_argIndex, index);
        try {
          __db.beginTransaction();
          try {
            _stmt.executeUpdateDelete();
            __db.setTransactionSuccessful();
            return Unit.INSTANCE;
          } finally {
            __db.endTransaction();
          }
        } finally {
          __preparedStmtOfDeletePhoto.release(_stmt);
        }
      }
    }, $completion);
  }

  @Override
  public Object deletePhotosOf(final String objectId,
      final Continuation<? super Unit> $completion) {
    return CoroutinesRoom.execute(__db, true, new Callable<Unit>() {
      @Override
      @NonNull
      public Unit call() throws Exception {
        final SupportSQLiteStatement _stmt = __preparedStmtOfDeletePhotosOf.acquire();
        int _argIndex = 1;
        _stmt.bindString(_argIndex, objectId);
        try {
          __db.beginTransaction();
          try {
            _stmt.executeUpdateDelete();
            __db.setTransactionSuccessful();
            return Unit.INSTANCE;
          } finally {
            __db.endTransaction();
          }
        } finally {
          __preparedStmtOfDeletePhotosOf.release(_stmt);
        }
      }
    }, $completion);
  }

  @Override
  public Object deleteObject(final String objectId, final Continuation<? super Unit> $completion) {
    return CoroutinesRoom.execute(__db, true, new Callable<Unit>() {
      @Override
      @NonNull
      public Unit call() throws Exception {
        final SupportSQLiteStatement _stmt = __preparedStmtOfDeleteObject.acquire();
        int _argIndex = 1;
        _stmt.bindString(_argIndex, objectId);
        try {
          __db.beginTransaction();
          try {
            _stmt.executeUpdateDelete();
            __db.setTransactionSuccessful();
            return Unit.INSTANCE;
          } finally {
            __db.endTransaction();
          }
        } finally {
          __preparedStmtOfDeleteObject.release(_stmt);
        }
      }
    }, $completion);
  }

  @Override
  public Flow<List<ObjectWithPhotos>> observeAllObjects() {
    final String _sql = "SELECT * FROM captured_objects ORDER BY createdAtEpochMs DESC";
    final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 0);
    return CoroutinesRoom.createFlow(__db, true, new String[] {"captured_photos",
        "captured_objects"}, new Callable<List<ObjectWithPhotos>>() {
      @Override
      @NonNull
      public List<ObjectWithPhotos> call() throws Exception {
        __db.beginTransaction();
        try {
          final Cursor _cursor = DBUtil.query(__db, _statement, true, null);
          try {
            final int _cursorIndexOfId = CursorUtil.getColumnIndexOrThrow(_cursor, "id");
            final int _cursorIndexOfCreatedAtEpochMs = CursorUtil.getColumnIndexOrThrow(_cursor, "createdAtEpochMs");
            final ArrayMap<String, ArrayList<CapturedPhotoEntity>> _collectionPhotos = new ArrayMap<String, ArrayList<CapturedPhotoEntity>>();
            while (_cursor.moveToNext()) {
              final String _tmpKey;
              _tmpKey = _cursor.getString(_cursorIndexOfId);
              if (!_collectionPhotos.containsKey(_tmpKey)) {
                _collectionPhotos.put(_tmpKey, new ArrayList<CapturedPhotoEntity>());
              }
            }
            _cursor.moveToPosition(-1);
            __fetchRelationshipcapturedPhotosAscomExampleArsurveyCaptureDataCapturedPhotoEntity(_collectionPhotos);
            final List<ObjectWithPhotos> _result = new ArrayList<ObjectWithPhotos>(_cursor.getCount());
            while (_cursor.moveToNext()) {
              final ObjectWithPhotos _item;
              final CapturedObjectEntity _tmpObj;
              final String _tmpId;
              _tmpId = _cursor.getString(_cursorIndexOfId);
              final long _tmpCreatedAtEpochMs;
              _tmpCreatedAtEpochMs = _cursor.getLong(_cursorIndexOfCreatedAtEpochMs);
              _tmpObj = new CapturedObjectEntity(_tmpId,_tmpCreatedAtEpochMs);
              final ArrayList<CapturedPhotoEntity> _tmpPhotosCollection;
              final String _tmpKey_1;
              _tmpKey_1 = _cursor.getString(_cursorIndexOfId);
              _tmpPhotosCollection = _collectionPhotos.get(_tmpKey_1);
              _item = new ObjectWithPhotos(_tmpObj,_tmpPhotosCollection);
              _result.add(_item);
            }
            __db.setTransactionSuccessful();
            return _result;
          } finally {
            _cursor.close();
          }
        } finally {
          __db.endTransaction();
        }
      }

      @Override
      protected void finalize() {
        _statement.release();
      }
    });
  }

  @Override
  public Object getObject(final String objectId,
      final Continuation<? super ObjectWithPhotos> $completion) {
    final String _sql = "SELECT * FROM captured_objects WHERE id = ?";
    final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 1);
    int _argIndex = 1;
    _statement.bindString(_argIndex, objectId);
    final CancellationSignal _cancellationSignal = DBUtil.createCancellationSignal();
    return CoroutinesRoom.execute(__db, true, _cancellationSignal, new Callable<ObjectWithPhotos>() {
      @Override
      @Nullable
      public ObjectWithPhotos call() throws Exception {
        __db.beginTransaction();
        try {
          final Cursor _cursor = DBUtil.query(__db, _statement, true, null);
          try {
            final int _cursorIndexOfId = CursorUtil.getColumnIndexOrThrow(_cursor, "id");
            final int _cursorIndexOfCreatedAtEpochMs = CursorUtil.getColumnIndexOrThrow(_cursor, "createdAtEpochMs");
            final ArrayMap<String, ArrayList<CapturedPhotoEntity>> _collectionPhotos = new ArrayMap<String, ArrayList<CapturedPhotoEntity>>();
            while (_cursor.moveToNext()) {
              final String _tmpKey;
              _tmpKey = _cursor.getString(_cursorIndexOfId);
              if (!_collectionPhotos.containsKey(_tmpKey)) {
                _collectionPhotos.put(_tmpKey, new ArrayList<CapturedPhotoEntity>());
              }
            }
            _cursor.moveToPosition(-1);
            __fetchRelationshipcapturedPhotosAscomExampleArsurveyCaptureDataCapturedPhotoEntity(_collectionPhotos);
            final ObjectWithPhotos _result;
            if (_cursor.moveToFirst()) {
              final CapturedObjectEntity _tmpObj;
              final String _tmpId;
              _tmpId = _cursor.getString(_cursorIndexOfId);
              final long _tmpCreatedAtEpochMs;
              _tmpCreatedAtEpochMs = _cursor.getLong(_cursorIndexOfCreatedAtEpochMs);
              _tmpObj = new CapturedObjectEntity(_tmpId,_tmpCreatedAtEpochMs);
              final ArrayList<CapturedPhotoEntity> _tmpPhotosCollection;
              final String _tmpKey_1;
              _tmpKey_1 = _cursor.getString(_cursorIndexOfId);
              _tmpPhotosCollection = _collectionPhotos.get(_tmpKey_1);
              _result = new ObjectWithPhotos(_tmpObj,_tmpPhotosCollection);
            } else {
              _result = null;
            }
            __db.setTransactionSuccessful();
            return _result;
          } finally {
            _cursor.close();
            _statement.release();
          }
        } finally {
          __db.endTransaction();
        }
      }
    }, $completion);
  }

  @NonNull
  public static List<Class<?>> getRequiredConverters() {
    return Collections.emptyList();
  }

  private void __fetchRelationshipcapturedPhotosAscomExampleArsurveyCaptureDataCapturedPhotoEntity(
      @NonNull final ArrayMap<String, ArrayList<CapturedPhotoEntity>> _map) {
    final Set<String> __mapKeySet = _map.keySet();
    if (__mapKeySet.isEmpty()) {
      return;
    }
    if (_map.size() > RoomDatabase.MAX_BIND_PARAMETER_CNT) {
      RelationUtil.recursiveFetchArrayMap(_map, true, (map) -> {
        __fetchRelationshipcapturedPhotosAscomExampleArsurveyCaptureDataCapturedPhotoEntity(map);
        return Unit.INSTANCE;
      });
      return;
    }
    final StringBuilder _stringBuilder = StringUtil.newStringBuilder();
    _stringBuilder.append("SELECT `rowId`,`objectId`,`photoIndex`,`imagePath`,`depthRawPath`,`capturedAtEpochMs`,`frameTimestampNs`,`trackingState`,`imageWidth`,`imageHeight`,`depthAvailable`,`depthWidth`,`depthHeight`,`metadataJson` FROM `captured_photos` WHERE `objectId` IN (");
    final int _inputSize = __mapKeySet.size();
    StringUtil.appendPlaceholders(_stringBuilder, _inputSize);
    _stringBuilder.append(")");
    final String _sql = _stringBuilder.toString();
    final int _argCount = 0 + _inputSize;
    final RoomSQLiteQuery _stmt = RoomSQLiteQuery.acquire(_sql, _argCount);
    int _argIndex = 1;
    for (String _item : __mapKeySet) {
      _stmt.bindString(_argIndex, _item);
      _argIndex++;
    }
    final Cursor _cursor = DBUtil.query(__db, _stmt, false, null);
    try {
      final int _itemKeyIndex = CursorUtil.getColumnIndex(_cursor, "objectId");
      if (_itemKeyIndex == -1) {
        return;
      }
      final int _cursorIndexOfRowId = 0;
      final int _cursorIndexOfObjectId = 1;
      final int _cursorIndexOfPhotoIndex = 2;
      final int _cursorIndexOfImagePath = 3;
      final int _cursorIndexOfDepthRawPath = 4;
      final int _cursorIndexOfCapturedAtEpochMs = 5;
      final int _cursorIndexOfFrameTimestampNs = 6;
      final int _cursorIndexOfTrackingState = 7;
      final int _cursorIndexOfImageWidth = 8;
      final int _cursorIndexOfImageHeight = 9;
      final int _cursorIndexOfDepthAvailable = 10;
      final int _cursorIndexOfDepthWidth = 11;
      final int _cursorIndexOfDepthHeight = 12;
      final int _cursorIndexOfMetadataJson = 13;
      while (_cursor.moveToNext()) {
        final String _tmpKey;
        _tmpKey = _cursor.getString(_itemKeyIndex);
        final ArrayList<CapturedPhotoEntity> _tmpRelation = _map.get(_tmpKey);
        if (_tmpRelation != null) {
          final CapturedPhotoEntity _item_1;
          final long _tmpRowId;
          _tmpRowId = _cursor.getLong(_cursorIndexOfRowId);
          final String _tmpObjectId;
          _tmpObjectId = _cursor.getString(_cursorIndexOfObjectId);
          final int _tmpPhotoIndex;
          _tmpPhotoIndex = _cursor.getInt(_cursorIndexOfPhotoIndex);
          final String _tmpImagePath;
          _tmpImagePath = _cursor.getString(_cursorIndexOfImagePath);
          final String _tmpDepthRawPath;
          if (_cursor.isNull(_cursorIndexOfDepthRawPath)) {
            _tmpDepthRawPath = null;
          } else {
            _tmpDepthRawPath = _cursor.getString(_cursorIndexOfDepthRawPath);
          }
          final long _tmpCapturedAtEpochMs;
          _tmpCapturedAtEpochMs = _cursor.getLong(_cursorIndexOfCapturedAtEpochMs);
          final Long _tmpFrameTimestampNs;
          if (_cursor.isNull(_cursorIndexOfFrameTimestampNs)) {
            _tmpFrameTimestampNs = null;
          } else {
            _tmpFrameTimestampNs = _cursor.getLong(_cursorIndexOfFrameTimestampNs);
          }
          final String _tmpTrackingState;
          if (_cursor.isNull(_cursorIndexOfTrackingState)) {
            _tmpTrackingState = null;
          } else {
            _tmpTrackingState = _cursor.getString(_cursorIndexOfTrackingState);
          }
          final Integer _tmpImageWidth;
          if (_cursor.isNull(_cursorIndexOfImageWidth)) {
            _tmpImageWidth = null;
          } else {
            _tmpImageWidth = _cursor.getInt(_cursorIndexOfImageWidth);
          }
          final Integer _tmpImageHeight;
          if (_cursor.isNull(_cursorIndexOfImageHeight)) {
            _tmpImageHeight = null;
          } else {
            _tmpImageHeight = _cursor.getInt(_cursorIndexOfImageHeight);
          }
          final boolean _tmpDepthAvailable;
          final int _tmp;
          _tmp = _cursor.getInt(_cursorIndexOfDepthAvailable);
          _tmpDepthAvailable = _tmp != 0;
          final Integer _tmpDepthWidth;
          if (_cursor.isNull(_cursorIndexOfDepthWidth)) {
            _tmpDepthWidth = null;
          } else {
            _tmpDepthWidth = _cursor.getInt(_cursorIndexOfDepthWidth);
          }
          final Integer _tmpDepthHeight;
          if (_cursor.isNull(_cursorIndexOfDepthHeight)) {
            _tmpDepthHeight = null;
          } else {
            _tmpDepthHeight = _cursor.getInt(_cursorIndexOfDepthHeight);
          }
          final String _tmpMetadataJson;
          _tmpMetadataJson = _cursor.getString(_cursorIndexOfMetadataJson);
          _item_1 = new CapturedPhotoEntity(_tmpRowId,_tmpObjectId,_tmpPhotoIndex,_tmpImagePath,_tmpDepthRawPath,_tmpCapturedAtEpochMs,_tmpFrameTimestampNs,_tmpTrackingState,_tmpImageWidth,_tmpImageHeight,_tmpDepthAvailable,_tmpDepthWidth,_tmpDepthHeight,_tmpMetadataJson);
          _tmpRelation.add(_item_1);
        }
      }
    } finally {
      _cursor.close();
    }
  }
}
