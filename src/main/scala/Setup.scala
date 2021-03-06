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

object setup {
  val env      = System.getenv()
  val endpoint = env.get("AWS_ENDPOINT")
  val bucket   = env.get("AWS_BUCKET")
  val region   = software.amazon.awssdk.regions.Region.US_EAST_1
}
