package me.joshuakfarrar.apollo.core

trait HasEmail[U]:
  def email(user: U): String
