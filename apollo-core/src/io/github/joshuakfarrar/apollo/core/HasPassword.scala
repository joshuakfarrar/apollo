package io.github.joshuakfarrar.apollo.core

trait HasPassword[U]:
  def password(user: U): String
