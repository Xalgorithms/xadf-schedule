// Copyright (C) 2018 Don Kelly <karfai@gmail.com>

// This file is part of Interlibr, a functional component of an
// Internet of Rules (IoR).

// ACKNOWLEDGEMENTS
// Funds: Xalgorithms Foundation
// Collaborators: Don Kelly, Joseph Potvin and Bill Olders.

// This program is free software: you can redistribute it and/or
// modify it under the terms of the GNU Affero General Public License
// as published by the Free Software Foundation, either version 3 of
// the License, or (at your option) any later version.

// This program is distributed in the hope that it will be useful, but
// WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
// Affero General Public License for more details.

// You should have received a copy of the GNU Affero General Public
// License along with this program. If not, see
// <http://www.gnu.org/licenses/>.
package actors

import akka.actor._
import akka.kafka.ProducerSettings
import akka.kafka.scaladsl.{ Producer }
import akka.stream.{ ActorMaterializer, OverflowStrategy }
import akka.stream.scaladsl.{ Flow, Source }
import javax.inject._
import java.io.ByteArrayOutputStream
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.serialization.StringSerializer
import play.api.libs.json._
import scala.util.Properties

object MessagesActor {
  def props = Props[MessagesActor]
}

class MessagesActor extends Actor with ActorLogging {
  implicit val materializer = ActorMaterializer()

  import Triggers._
  import Actions.InvokeTrigger
  import Implicits.trigger_writes

  private val broker = Properties.envOrElse("KAFKA_BROKER", "kafka:9092")
  log.info(s"creating kafka settings (broker=${broker})")

  private val settings = ProducerSettings(
    context.system, new StringSerializer, new StringSerializer
  ).withBootstrapServers(broker)

  private val _source = Source.queue[InvokeTrigger](5, OverflowStrategy.backpressure)
  private val _flow_json = Flow[InvokeTrigger].map { o =>
    (o.topic, Json.toJson(o.trigger))
  }
  private val _flow_record = Flow[(String, JsValue)].map { case (topic, payload) =>
    new ProducerRecord[String, String](topic, payload.toString)
  }

  log.info("setting up stream")
  val _triggers = _source.via(_flow_json).via(_flow_record).to(Producer.plainSink(settings)).run()

  def trigger_document_on_topic(
    topic: String,
    doc_id: String,
    opt_effective_ctxs: Option[Seq[Map[String, String]]] = None
  ) = {
    opt_effective_ctxs match {
      case Some(effective_ctxs) => {
        effective_ctxs.foreach { effective_ctx =>
          send(topic, TriggerDocument(doc_id, effective_ctx))
        }
      }
      case None => log.debug("no context supplied")
    }
  }

  def receive = {
    case GlobalMessages.SubmissionAdded(doc_id, opt_effective_ctxs) => {
      trigger_document_on_topic("il.compute.execute", doc_id, opt_effective_ctxs)
    }

    case GlobalMessages.EffectiveVerificationAdded(doc_id, opt_effective_ctxs) => {
      trigger_document_on_topic("il.verify.effective", doc_id, opt_effective_ctxs)
    }

    case GlobalMessages.ApplicableVerificationAdded(doc_id, rule_id) => {
      send("il.verify.applicable", TriggerApplicable(doc_id, rule_id))
    }

    case GlobalMessages.ExecutionAdded(id) => {
      send("il.verify.rule_execution", TriggerById(id))
    }
  }

  private def send(topic: String, trigger: Trigger) = {
    log.debug(s"> sending message (topic=${topic})")
    _triggers.offer(InvokeTrigger(topic, trigger))
    log.debug(s"< sent message (topic=${topic})")
  }
}
