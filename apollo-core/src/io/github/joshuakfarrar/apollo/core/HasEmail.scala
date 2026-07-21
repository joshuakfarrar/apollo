package io.github.joshuakfarrar.apollo.core

trait HasEmail[U]:
  def email(user: U): String
