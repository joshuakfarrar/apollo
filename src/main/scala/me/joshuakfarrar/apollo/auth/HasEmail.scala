package me.joshuakfarrar.apollo.auth

trait HasEmail[U]:
  def email(user: U): String