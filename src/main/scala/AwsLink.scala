/*
 * Copyright 2019 io.github.neurodyne
 *
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
 */

package zio_aws_s3

import java.nio.file.{ Paths }
import java.util.concurrent.CompletableFuture
import scala.jdk.CollectionConverters._
import com.github.ghik.silencer.silent

import zio.{ Has, IO, Task, ZIO, ZLayer }
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3AsyncClient
import software.amazon.awssdk.services.s3.model.{
  AccessControlPolicy,
  CopyObjectRequest,
  CopyObjectResponse,
  CreateBucketRequest,
  CreateBucketResponse,
  DeleteBucketRequest,
  DeleteBucketResponse,
  DeleteObjectRequest,
  DeleteObjectResponse,
  GetObjectAclRequest,
  GetObjectAclResponse,
  GetObjectRequest,
  GetObjectResponse,
  Grant,
  ListBucketsResponse,
  ListObjectsV2Request,
  ListObjectsV2Response,
  Owner,
  Permission,
  PutObjectAclRequest,
  PutObjectAclResponse,
  PutObjectRequest,
  PutObjectResponse,
  Type
}

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.{ List => JList }
import java.net.URI

object AwsAgent {
  def createClient(region: Region, endpoint: String): Task[S3AsyncClient] = {
    val client =
      if (endpoint.isEmpty)
        S3AsyncClient.builder
          .region(region)
          .build
      else
        S3AsyncClient.builder
          .region(region)
          .endpointOverride(URI.create(endpoint))
          .build
    Task(client)
  }
}

package object AwsApp {

  type ExtDeps = Has[ExtDeps.Service]

  object ExtDeps {
    trait Service {
      val s3: S3AsyncClient
    }

    val any: ZLayer[ExtDeps, Nothing, ExtDeps] =
      ZLayer.requires[ExtDeps]

    val live = ZLayer.fromFunction((curr: S3AsyncClient) => new ExtDeps.Service { val s3 = curr })
  }

  type AwsLink = Has[AwsLink.Service[Any]]

  object AwsLink {

    trait Service[R] extends GenericLink[R] {}

    val any: ZLayer[AwsLink, Nothing, AwsLink] =
      ZLayer.requires[AwsLink]

    val live: ZLayer[ExtDeps, Throwable, AwsLink] = ZLayer.fromService { (deps: ExtDeps.Service) =>
      new Service[Any] {

        def createBucket(buck: String): Task[CreateBucketResponse] =
          IO.effectAsync[Throwable, CreateBucketResponse] { callback =>
            processResponse(
              deps.s3.createBucket(CreateBucketRequest.builder.bucket(buck).build),
              callback
            )
          }
        def delBucket(buck: String): Task[DeleteBucketResponse] =
          IO.effectAsync[Throwable, DeleteBucketResponse] { callback =>
            processResponse(
              deps.s3.deleteBucket(DeleteBucketRequest.builder.bucket(buck).build),
              callback
            )
          }

        def listBuckets: Task[ListBucketsResponse] =
          IO.effectAsync[Throwable, ListBucketsResponse](callback => processResponse(deps.s3.listBuckets, callback))

        def listBucketObjects(buck: String, prefix: String): Task[ListObjectsV2Response] =
          for {
            resp <- IO.effect(
                     deps.s3.listObjectsV2(
                       ListObjectsV2Request.builder
                         .bucket(buck)
                         .maxKeys(20)
                         .prefix(prefix)
                         .build
                     )
                   )
            list <- IO.effectAsync[Throwable, ListObjectsV2Response] { callback =>
                     processResponse(
                       resp,
                       callback
                     )
                   }
          } yield list

        def listObjectsKeys(buck: String, prefix: String): Task[List[String]] =
          for {
            list <- listBucketObjects(buck, prefix)
            keys = list.contents.asScala.map(_.key).toList
            _    = println(s">>>>>> Total keys found for prefix ${prefix}: ${keys.size}")
          } yield keys

        def lookupObject(buck: String, prefix: String, key: String): Task[Boolean] =
          for {
            list   <- listBucketObjects(buck, prefix)
            newKey = prefix + "/" + key
            res = list.contents.asScala
              .filter(_.key == newKey)
              .nonEmpty
            _ = println(res)
          } yield res

        def getObjectAcl(buck: String, key: String): Task[GetObjectAclResponse] = {
          val req = GetObjectAclRequest.builder.bucket(buck).key(key).build

          println(s">>>>>> Get ACL for key: ${key}")

          IO.effectAsync[Throwable, GetObjectAclResponse](callback =>
              processResponse(deps.s3.getObjectAcl(req), callback)
            )
            .mapError(_ => new Throwable("Failed Processing CopyObjectResponse"))
        }

        def putObjectAcl(buck: String, key: String, owner: Owner, grants: JList[Grant]): Task[PutObjectAclResponse] =
          for {
            acl <- Task.effect(AccessControlPolicy.builder.owner(owner).grants(grants).build)
            _   = println(s">>>>>> PUT ACL for key: ${key}")
            req <- Task.effect(PutObjectAclRequest.builder.bucket(buck).key(key).accessControlPolicy(acl).build)
            rsp <- IO
                    .effectAsync[Throwable, PutObjectAclResponse] { callback =>
                      processResponse(deps.s3.putObjectAcl(req), callback)
                    }
                    .mapError(_ => new Throwable("Failed Processing PutObjectAclResponse"))
          } yield rsp

        def redirectPack(buck: String, prefix: String, url: String): Task[Unit] =
          for {
            keys <- listObjectsKeys(buck, prefix)
            _    = Task.foreach(keys)(key => redirectObject(buck, prefix, key, url))
          } yield ()

        def blockPack(buck: String, prefix: String): Task[Unit] =
          putPackAcl(buck, prefix, true).as(())

        def unblockPack(buck: String, prefix: String): Task[Unit] =
          putPackAcl(buck, prefix, false).as(())

        def getPackAcl(buck: String, prefix: String): Task[List[GetObjectAclResponse]] =
          for {
            keys <- listObjectsKeys(buck, prefix)
            list <- Task.foreach(keys)(key => getObjectAcl(buck, key))
          } yield list

        @silent("discarded non-Unit value")
        def putPackAcl(buck: String, prefix: String, block: Boolean): Task[List[PutObjectAclResponse]] =
          for {
            keys <- listObjectsKeys(buck, prefix)
            acl <- getObjectAcl(
                    buck,
                    keys.head
                  ) // read ACL for the first element in a pack. Assume all others have the same ACL in the pack
            owner = acl.owner // Evaluate owner and grants to avoid multiple calls
            grGrant <- Task.effect(
                        Grant
                          .builder()
                          .grantee { bld =>
                            bld
                              .id("dev-assets")
                              .`type`(Type.CANONICAL_USER)
                              .displayName("DEV Assets User")
                          }
                          .permission(Permission.FULL_CONTROL)
                          .grantee { bld =>
                            bld
                              .`type`(Type.GROUP)
                              .uri("http://acs.amazonaws.com/groups/global/AllUsers")
                          }
                          .permission(Permission.READ)
                          .build
                      )
            grants = if (block) List.empty[Grant] else List(grGrant)
            list   <- Task.foreach(keys)(key => putObjectAcl(buck, key, acl.owner, grants.asJava))
          } yield list

        def redirectObject(buck: String, prefix: String, key: String, url: String): Task[CopyObjectResponse] = {
          val dstPrefix = prefix + "/" + url
          copyObject(buck, dstPrefix, key, key) // copy each object inside the same buck, but with diff indexes
        }

        def copyObject(buck: String, dstPrefix: String, srcKey: String, dstKey: String): Task[CopyObjectResponse] = {

          val src = URLEncoder.encode(buck + "/" + srcKey, StandardCharsets.UTF_8.toString)
          val dst = URLEncoder.encode(buck + "/" + dstKey, StandardCharsets.UTF_8.toString)

          println(s">>>>>> Copy Src Link: ${src}")
          println(s">>>>>> Copy Dst link: ${dst}")

          for {
            req <- IO.effect(
                    CopyObjectRequest.builder
                      .destinationBucket(buck)
                      .destinationKey(dstKey)
                      .copySource(src)
                      .websiteRedirectLocation(dst)
                      .build
                  )
            rsp <- IO
                    .effectAsync[Throwable, CopyObjectResponse] { callback =>
                      processResponse(deps.s3.copyObject(req), callback)
                    }
                    .mapError(_ => new Throwable("Failed Processing CopyObjectResponse"))
          } yield rsp
        }

        def putObject(buck: String, key: String, file: String): Task[PutObjectResponse] =
          IO.effectAsync[Throwable, PutObjectResponse] { callback =>
            processResponse(
              deps.s3.putObject(PutObjectRequest.builder.bucket(buck).key(key).build, Paths.get(file)),
              callback
            )
          }

        def getObject(buck: String, key: String, file: String): Task[GetObjectResponse] =
          IO.effectAsync[Throwable, GetObjectResponse] { callback =>
            processResponse(
              deps.s3.getObject(GetObjectRequest.builder.bucket(buck).key(key).build, Paths.get(file)),
              callback
            )
          }

        def delObject(buck: String, key: String): Task[DeleteObjectResponse] =
          IO.effectAsync[Throwable, DeleteObjectResponse] { callback =>
            processResponse(
              deps.s3.deleteObject(DeleteObjectRequest.builder.bucket(buck).key(key).build),
              callback
            )
          }

        def delAllObjects(buck: String, prefix: String): Task[Unit] =
          for {
            keys <- listObjectsKeys(buck, prefix)
            _    <- Task.foreach(keys)(key => delObject(buck, key))
          } yield ()

        @silent("discarded non-Unit value")
        def processResponse[T](
          fut: CompletableFuture[T],
          callback: Task[T] => Unit
        ): Unit =
          fut.handle[Unit] { (response, err) =>
            err match {
              case null => callback(IO.succeed(response))
              case ex   => callback(IO.fail(ex))
            }
          }: Unit
      }
    }

  }
  def createBucket(buck: String): ZIO[AwsLink, Throwable, CreateBucketResponse] = ZIO.accessM(_.get.createBucket(buck))

  def delBucket(buck: String): ZIO[AwsLink, Throwable, DeleteBucketResponse] = ZIO.accessM(_.get.delBucket(buck))

  def listBuckets(): ZIO[AwsLink, Throwable, ListBucketsResponse] = ZIO.accessM(_.get.listBuckets)

  def listBucketObjects(buck: String, prefix: String): ZIO[AwsLink, Throwable, ListObjectsV2Response] =
    ZIO.accessM(_.get.listBucketObjects(buck, prefix))

  def listObjectsKeys(buck: String, prefix: String): ZIO[AwsLink, Throwable, List[String]] =
    ZIO.accessM(_.get.listObjectsKeys(buck, prefix))

  def lookupObject(buck: String, prefix: String, key: String): ZIO[AwsLink, Throwable, Boolean] =
    ZIO.accessM(_.get.lookupObject(buck, prefix, key))

  def redirectObject(
    buck: String,
    prefix: String,
    key: String,
    url: String
  ): ZIO[AwsLink, Throwable, CopyObjectResponse] =
    ZIO.accessM(_.get.redirectObject(buck, prefix, key, url))

  def redirectPack(buck: String, prefix: String, url: String): ZIO[AwsLink, Throwable, Unit] =
    ZIO.accessM(_.get.redirectPack(buck, prefix, url))

  def copyObject(
    buck: String,
    dstPrefix: String,
    srcKey: String,
    dstKey: String
  ): ZIO[AwsLink, Throwable, CopyObjectResponse] =
    ZIO.accessM(_.get.copyObject(buck, dstPrefix, srcKey, dstKey))

  def putObject(buck: String, key: String, file: String): ZIO[AwsLink, Throwable, PutObjectResponse] =
    ZIO.accessM(_.get.putObject(buck, key, file))

  def getObject(buck: String, key: String, file: String): ZIO[AwsLink, Throwable, GetObjectResponse] =
    ZIO.accessM(_.get.getObject(buck, key, file))

  def delObject(buck: String, key: String): ZIO[AwsLink, Throwable, DeleteObjectResponse] =
    ZIO.accessM(_.get.delObject(buck, key))

  def delAllObjects(buck: String, prefix: String): ZIO[AwsLink, Throwable, Unit] =
    ZIO.accessM(_.get.delAllObjects(buck, prefix))

  def getObjectAcl(buck: String, key: String): ZIO[AwsLink, Throwable, GetObjectAclResponse] =
    ZIO.accessM(_.get.getObjectAcl(buck, key))

  def putObjectAcl(
    buck: String,
    key: String,
    owner: Owner,
    grants: JList[Grant]
  ): ZIO[AwsLink, Throwable, PutObjectAclResponse] =
    ZIO.accessM(_.get.putObjectAcl(buck, key, owner, grants))

  def blockPack(buck: String, prefix: String): ZIO[AwsLink, Throwable, Unit] =
    ZIO.accessM(_.get.blockPack(buck, prefix))

  def unblockPack(buck: String, prefix: String): ZIO[AwsLink, Throwable, Unit] =
    ZIO.accessM(_.get.unblockPack(buck, prefix))

  def getPackAcl(buck: String, prefix: String): ZIO[AwsLink, Throwable, List[GetObjectAclResponse]] =
    ZIO.accessM(_.get.getPackAcl(buck, prefix))

  def putPackAcl(buck: String, prefix: String, block: Boolean): ZIO[AwsLink, Throwable, List[PutObjectAclResponse]] =
    ZIO.accessM(_.get.putPackAcl(buck, prefix, block))

}
