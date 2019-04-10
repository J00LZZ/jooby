/**
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Copyright 2014 Edgar Espina
 */
package io.jooby;

import javax.annotation.Nonnull;
import java.time.Duration;
import java.util.Date;
import java.util.List;

/**
 * Handler for static resources represented by the {@link Asset} contract.
 *
 * @author edgar
 * @since 2.0.0
 */
public class AssetHandler implements Route.Handler {
  private final AssetSource[] sources;

  private boolean etag = true;

  private boolean lastModified = true;

  private long maxAge = -1;

  private String filekey;

  /**
   * Creates a new asset handler.
   *
   * @param sources Asset sources.
   */
  public AssetHandler(AssetSource... sources) {
    this.sources = sources;
  }

  @Nonnull @Override public Object apply(@Nonnull Context ctx) throws Exception {
    String filepath = ctx.pathMap().get(filekey);
    Asset asset = resolve(filepath);
    if (asset == null) {
      ctx.sendStatusCode(StatusCode.NOT_FOUND);
      return ctx;
    }

    // handle If-None-Match
    if (this.etag) {
      String ifnm = ctx.header("If-None-Match").value((String) null);
      if (ifnm != null && ifnm.equals(asset.getEtag())) {
        ctx.sendStatusCode(StatusCode.NOT_MODIFIED);
        asset.release();
        return ctx;
      } else {
        ctx.setHeader("ETag", asset.getEtag());
      }
    }

    // Handle If-Modified-Since
    if (this.lastModified) {
      long lastModified = asset.getLastModified();
      if (lastModified > 0) {
        long ifms = ctx.header("If-Modified-Since").longValue(-1);
        if (lastModified <= ifms) {
          ctx.sendStatusCode(StatusCode.NOT_MODIFIED);
          asset.release();
          return ctx;
        }
        ctx.setHeader("Last-Modified", new Date(lastModified));
      }
    }

    // cache max-age
    if (maxAge > 0) {
      ctx.setHeader("Cache-Control", "max-age=" + maxAge);
    }

    long length = asset.getSize();
    if (length != -1) {
      ctx.setContentLength(length);
    }
    ctx.setContentType(asset.getContentType());
    return ctx.sendStream(asset.stream());
  }

  /**
   * Turn on/off e-tag support.
   *
   * @param etag True for turning on.
   * @return This handler.
   */
  public AssetHandler setETag(boolean etag) {
    this.etag = etag;
    return this;
  }

  /**
   * Turn on/off handling of <code>If-Modified-Since</code> header.
   *
   * @param lastModified True for turning on. Default is: true.
   * @return This handler.
   */
  public AssetHandler setLastModified(boolean lastModified) {
    this.lastModified = lastModified;
    return this;
  }

  /**
   * Set cache-control header with the given max-age value. If max-age is greater than 0.
   *
   * @param maxAge Max-age value in seconds.
   * @return This handler.
   */
  public AssetHandler setMaxAge(long maxAge) {
    this.maxAge = maxAge;
    return this;
  }

  /**
   * Set cache-control header with the given max-age value. If max-age is greater than 0.
   *
   * @param maxAge Max-age value in seconds.
   * @return This handler.
   */
  public AssetHandler setMaxAge(Duration maxAge) {
    this.maxAge = maxAge.getSeconds();
    return this;
  }

  private Asset resolve(String filepath) {
    for (AssetSource source : sources) {
      Asset asset = source.resolve(filepath);
      if (asset != null) {
        return asset;
      }
    }
    return null;
  }

  @Override public Route.Handler setRoute(Route route) {
    List<String> keys = route.getPathKeys();
    this.filekey = keys.size() == 0 ? "*" : keys.get(0);

    // NOTE: It send an inputstream we don't need a renderer
    route.setReturnType(Context.class);
    return this;
  }
}