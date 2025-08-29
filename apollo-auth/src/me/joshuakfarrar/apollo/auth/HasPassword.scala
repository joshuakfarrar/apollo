package me.joshuakfarrar.apollo.auth

trait HasPassword[U]:
  def password(user: U): String