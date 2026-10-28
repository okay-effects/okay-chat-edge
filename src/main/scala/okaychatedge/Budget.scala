package okaychatedge

import okay.effects.json.Json
import okay.http.{Request, Response}
import okay.scala3.*
import okay.scala3.std.*
import okay.serverless.cloud.*
import okay.serverless.Durable

/**
 * THE DAY'S BUDGET for the model lane, okay-chat's cap: at most `LANE_DAILY` replies a day (UTC), 2000 unless set —
 * ~8000 of the free plan's 10 000 neurons, the rest left to bge-m3. Past it the canned answer is said, and the free
 * plan's own limit is never what stops a reply. One object counts, each reply one step.
 */
object Budget:
  val default = 2000

  /** a reply granted — or not, the day's spent */
  def take(at: Long): Boolean ! Cloud =
    Env.variable[Cloud]("LANE_DAILY").flatMap { d =>
      val limit = d.flatMap(_.trim.toIntOption).getOrElse(default)
      Objects.call[Cloud]("BUDGET", "lane", Request.json("POST", "/", Json.obj(
        "day" -> Json.Num(BigDecimal(at / 86400000L)), "limit" -> Json.Num(BigDecimal(limit)))))
        .map(_.body.trim == "yes")
    }

  object Days extends Actor:
    def fetch(name: String, request: Request): Response ! InActor =
      val j = okay.effects.json.Descent.parse(request.body).toOption.getOrElse(Json.Null)
      val num = (k: String) => j match
        case Json.Obj(fs) => fs.collectFirst { case (`k`, Json.Num(n)) => n.toLong }.getOrElse(0L)
        case _ => 0L
      val (day, limit) = (num("day"), num("limit"))
      Durable.step[ActorStep, InActor, Boolean](name)(Durable.get[Long, ActorStep]("day").flatMap { d =>
        Durable.get[Long, ActorStep]("used").flatMap { u =>
          val used = if d.contains(day) then u.getOrElse(0L) else 0L
          if used >= limit then pure(false)
          else Durable.put[Long, ActorStep]("day", day).andThen(Durable.put[Long, ActorStep]("used", used + 1)).as(true)
        }
      }).map(ok => Response.text(if ok then "yes" else "no"))
