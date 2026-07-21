package me.joshuakfarrar.apollo.core

trait HasPassword[U]:
  def password(user: U): String
