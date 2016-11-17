package jp.ne.opt.redshiftfake.parse

import jp.ne.opt.redshiftfake._
import jp.ne.opt.redshiftfake.s3.S3Location

import scala.util.parsing.combinator.RegexParsers

object CopyCommandParser extends RegexParsers {
  case class TableAndSchemaName(schemaName: Option[String], tableName: String)

  def identifier = """[_a-zA-Z]\w*"""

  def space = """\s*"""

  def tableNameParser = ((identifier.r <~ ".").? ~ identifier.r) ^^ {
    case ~(schemaName, tableName) => TableAndSchemaName(schemaName, tableName)
  }

  def columnListParser = """\(\s*""".r ~> ((identifier.r <~ """\s*,\s*""".r).* ~ identifier.r) <~ """\s*\)""".r ^^ {
    case ~(init, last) => init :+ last
  }

  def s3LocationParser = Global.s3Endpoint ~> """[\w-]+""".r ~ ("/" ~> """[\w/:%#$&?()~.=+-]+""".r).? ^^ {
    case ~(bucket, prefix) => S3Location(bucket, prefix.getOrElse(""))
  }

  def dataSourceParser = {
    def s3SourceParser = "'" ~> s3LocationParser <~ "'" ^^ CopyDataSource.S3
    s3SourceParser
  }

  def awsAuthArgsParser = {
    def parserWithKey = ("aws_access_key_id=" ~> """\w+""".r) ~ (";aws_secret_access_key=" ~> """\w+""".r) ^^ {
      case ~(accessKeyId, secretAccessKey) => Credentials.WithKey(accessKeyId, secretAccessKey)
    }

    "'" ~> parserWithKey <~ "'"
  }

  def copyFormatParser = {
    def json = ("(?i)JSON".r ~> space.r ~> "(?i)AS".r.? ~> space.r ~> ("'" ~> s3LocationParser <~ "'").?) ^^ CopyFormat.Json

    "(?i)FORMAT".r.? ~> space.r ~> "(?i)AS".r.? ~> space.r ~> json.? ^^ (_.getOrElse(CopyFormat.Default))
  }

  def parse(query: String): Option[CopyCommand] = {
    val result = parse(
      (s"$space(?i)COPY$space".r ~> tableNameParser <~ space.r) ~
        (columnListParser.? <~ space.r) ~
        (s"(?i)FROM$space".r ~> dataSourceParser <~ space.r) ~
        ("(?i)WITH".r.? ~> space.r ~> s"(?i)CREDENTIALS$space".r ~> "(?i)AS".r.? ~> space.r ~> awsAuthArgsParser <~ space.r) ~
        (copyFormatParser <~ space.r) <~
        s""".*""".r ^^ { case ~(~(~(~(TableAndSchemaName(schemaName, tableName), columnList), dataSource), auth), format) =>
        CopyCommand(schemaName, tableName, columnList, dataSource, auth, format)
      },
      query
    )
    if (result.isEmpty) None else Some(result.get)
  }
}