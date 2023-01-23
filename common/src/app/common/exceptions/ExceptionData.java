/*
  This Source Code Form is subject to the terms of the Mozilla Public
  License, v. 2.0. If a copy of the MPL was not distributed with this
  file, You can obtain one at http://mozilla.org/MPL/2.0/.

  Copyright (c) KALEIDOS INC
*/

package app.common.exceptions;

import clojure.lang.Cons;
import clojure.lang.IExceptionInfo;
import clojure.lang.IFn;
import clojure.lang.IPersistentMap;
import clojure.lang.PersistentArrayMap;
import clojure.lang.IReference;
import clojure.lang.ISeq;
import clojure.lang.IObj;

public class ExceptionData extends RuntimeException implements IExceptionInfo, IReference, IObj {
  public final IPersistentMap data;
  public IPersistentMap metadata;

  public ExceptionData(String message, IPersistentMap data) {
    this(message, data, null, null);
  }

  public ExceptionData(String message, IPersistentMap data, IPersistentMap meta) {
    this(message, data, meta, null);
  }

  public ExceptionData(String message, IPersistentMap data, IPersistentMap meta, Throwable throwable) {
    super(message, throwable);
    this.metadata = meta;

    if (data != null) {
      this.data = data;
    }  else {
      this.data = PersistentArrayMap.EMPTY;
    }
  }

  public static ExceptionData create(String message, IPersistentMap data, IPersistentMap meta, Throwable cause) {
    return new ExceptionData(message, data, meta, cause);
  }

  public IPersistentMap getData() {
    return data;
  }

  public IPersistentMap meta() {
    return metadata;
  }

  public IPersistentMap alterMeta(IFn alter, ISeq args) {
    this.metadata = (IPersistentMap) alter.applyTo(new Cons(this.metadata, args));
    return this.metadata;
  }

  public IPersistentMap resetMeta(IPersistentMap m) {
    this.metadata = m;
    return this.metadata;

  }

  public IObj withMeta(IPersistentMap meta) {
    return new ExceptionData(this.getMessage(), this.data, meta, this.getCause());
  }

  public String toString() {
    return "ExceptionData: " + getMessage() + " " + data.toString();
  }
}
